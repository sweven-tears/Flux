package cn.luoluna.sweven.ai.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.db.VideoJobEntity
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 视频生成任务管理器（App 级单例）：提交落库 → 10s 轮询 → 下载 mp4 → 落助手消息 → 通知。
 * **无硬超时**：一直轮询直到上游返回终态（completed/failed）或用户主动取消；
 * 每等待满 10 分钟把通知换成「继续等待吗？」询问（2 分钟无操作默认继续还原常规通知），
 * 「取消任务」按钮常驻。轮询在自有 scope 里跑，进程被杀后由服务/App 启动恢复。
 */
class VideoJobManager private constructor(private val context: Context) {

    private val app = context.applicationContext as App
    private val dao = app.database.videoJobDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val polling = ConcurrentHashMap<Long, Job>()

    /** 「继续等待」信号：用户点通知按钮后由服务写入，轮询循环读取后复位询问窗 */
    private val keepSignals = ConcurrentHashMap<Long, Boolean>()

    companion object {
        private const val POLL_INTERVAL_MS = 10_000L

        /** 每等待满该时长，通知询问一次「是否继续等待」 */
        private const val ASK_INTERVAL_MS = 10 * 60_000L

        /** 询问窗时长：窗内无操作默认继续等待 */
        private const val ASK_WINDOW_MS = 2 * 60_000L

        @Volatile
        private var instance: VideoJobManager? = null

        fun get(context: Context): VideoJobManager =
            instance ?: synchronized(this) {
                instance ?: VideoJobManager(context.applicationContext).also { instance = it }
            }
    }

    /** 活跃任务数（聊天页「视频生成中」提示） */
    fun observeActiveCount(): Flow<Int> = dao.observeActive().map { it.size }

    fun hasActiveJobs(): Boolean = polling.isNotEmpty()

    /**
     * 提交视频生成任务（挂起等待提交结果，由聊天页调用）：
     * 成功落 running 任务并启动轮询与前台服务；失败落 failed 行并把错误抛回调用方。
     */
    suspend fun submit(
        conversationId: Long,
        provider: ProviderEntity,
        apiKey: String,
        model: ModelEntity,
        prompt: String,
    ): Result<Unit> = runCatching {
        val headers = parseCustomHeaders(model.customHeaders)
        val extraBody = model.customBody?.takeIf { it.isNotBlank() }?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val upstreamId = app.providerClient.submitVideo(
            provider.baseUrl, apiKey, model.name, prompt, headers, extraBody,
        ).getOrThrow()
        val id = dao.insert(
            VideoJobEntity(
                conversationId = conversationId,
                providerId = provider.id,
                modelName = model.name,
                prompt = prompt,
                upstreamJobId = upstreamId,
                status = VideoJobEntity.STATUS_RUNNING,
            ),
        )
        startPolling(id)
        ensureService()
    }.onFailure { e ->
        dao.insert(
            VideoJobEntity(
                conversationId = conversationId,
                providerId = provider.id,
                modelName = model.name,
                prompt = prompt,
                status = VideoJobEntity.STATUS_FAILED,
                error = e.message ?: "提交失败",
            ),
        )
    }

    /** 进程/服务重启后恢复未完成任务（提交中断、无上游 id 的行标记失败） */
    fun resumeAll() {
        scope.launch {
            val pending = dao.active()
            pending.forEach { job ->
                if (job.upstreamJobId != null) {
                    startPolling(job.id)
                } else {
                    dao.update(
                        job.copy(
                            status = VideoJobEntity.STATUS_FAILED,
                            error = "任务提交中断",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            if (pending.isNotEmpty()) ensureService()
        }
    }

    /** 用户取消（通知按钮）：停轮询、尽力取消上游、清通知 */
    fun cancel(jobId: Long) {
        scope.launch {
            val job = dao.byId(jobId) ?: return@launch
            if (job.status != VideoJobEntity.STATUS_PENDING && job.status != VideoJobEntity.STATUS_RUNNING) {
                return@launch
            }
            dao.update(
                job.copy(
                    status = VideoJobEntity.STATUS_CANCELLED,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            polling.remove(jobId)?.cancel()
            val provider = app.database.providerDao().byId(job.providerId)
            val key = provider?.let { app.modelRouter.activeKey(it) }?.key
            if (provider != null && key != null && job.upstreamJobId != null) {
                // 尽力通知上游取消，失败忽略（无硬超时语义下上游自然终态也不影响本地）
                runCatching {
                    app.providerClient.cancelVideo(
                        provider.baseUrl, key, job.upstreamJobId,
                        parseCustomHeaders(app.database.modelDao().find(provider.id, job.modelName)?.customHeaders),
                    )
                }
            }
            Notifications.cancelVideoNotification(context, jobId)
        }
    }

    /** 用户点「继续等待」：复位询问窗并顺延到下一个 10 分钟节点 */
    fun onKeepWaiting(jobId: Long) {
        keepSignals[jobId] = true
    }

    /** 前台服务启动用的通知：取最早一个活跃任务（通知 ID 与轮询循环更新的同一槽位） */
    fun firstActiveForForeground(): Pair<Int, android.app.Notification> {
        val job = dao.activeBlocking().firstOrNull()
        return if (job == null) {
            // 理论不会发生（调用前已判活跃）；兜底空态通知避免 startForeground 无通知崩溃
            Notifications.videoNotificationId(0L) to
                Notifications.buildVideoProgress(context, 0L, 0L, "视频任务", 0, false)
        } else {
            val elapsedMin = ((System.currentTimeMillis() - job.createdAt) / 60_000L).toInt()
            Notifications.videoNotificationId(job.id) to
                Notifications.buildVideoProgress(
                    context, job.id, job.conversationId, job.modelName, elapsedMin, false,
                )
        }
    }

    private fun ensureService() {
        ContextCompat.startForegroundService(context, Intent(context, VideoJobService::class.java))
    }

    private fun startPolling(jobId: Long) {
        if (polling.containsKey(jobId)) return
        polling[jobId] = scope.launch { pollLoop(jobId) }
    }

    private suspend fun pollLoop(jobId: Long) {
        try {
            var waitedMs = 0L
            var askedAtMs = -1L
            while (coroutineContext.isActive) {
                val job = dao.byId(jobId) ?: return
                if (job.status != VideoJobEntity.STATUS_PENDING && job.status != VideoJobEntity.STATUS_RUNNING) {
                    return
                }
                val upstreamId = job.upstreamIdOrThrow()
                val provider = app.database.providerDao().byId(job.providerId)
                if (provider == null || !provider.enabled) {
                    failJob(job, "厂商已删除或停用")
                    return
                }
                val key = app.modelRouter.activeKey(provider)?.key
                if (key.isNullOrBlank()) {
                    failJob(job, "厂商缺少 API Key")
                    return
                }
                val headers = parseCustomHeaders(
                    app.database.modelDao().find(provider.id, job.modelName)?.customHeaders,
                )

                val task = app.providerClient.queryVideo(provider.baseUrl, key, upstreamId, headers)
                when {
                    // 任务不存在（404）为终态；其余查询抖动不终止——无硬超时，继续轮询
                    task.isFailure && task.exceptionOrNull() is HttpApiException &&
                        (task.exceptionOrNull() as HttpApiException).httpCode == 404 -> {
                        failJob(job, "上游任务不存在（可能已被清理）")
                        return
                    }
                    task.isSuccess -> {
                        val t = task.getOrThrow()
                        when (normalizeStatus(t.status)) {
                            "completed" -> {
                                completeJob(job, provider, key, headers, upstreamId)
                                return
                            }
                            "failed" -> {
                                failJob(job, t.error ?: "上游任务失败")
                                return
                            }
                            else -> dao.update(
                                job.copy(
                                    status = VideoJobEntity.STATUS_RUNNING,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }

                if (keepSignals.remove(jobId) == true && askedAtMs > 0) {
                    // 用户明确「继续等待」：顺延到下一个 10 分钟节点再问
                    askedAtMs = -1L
                    waitedMs = (waitedMs / ASK_INTERVAL_MS + 1) * ASK_INTERVAL_MS
                }
                delay(POLL_INTERVAL_MS)
                waitedMs += POLL_INTERVAL_MS
                if (askedAtMs < 0 && waitedMs % ASK_INTERVAL_MS == 0L) {
                    askedAtMs = System.currentTimeMillis()
                }
                val asking = askedAtMs > 0 && System.currentTimeMillis() - askedAtMs < ASK_WINDOW_MS
                if (askedAtMs > 0 && !asking) askedAtMs = -1L
                Notifications.notifyVideoProgress(
                    context, jobId, job.conversationId, job.modelName,
                    (waitedMs / 60_000).toInt(), asking,
                )
            }
        } finally {
            polling.remove(jobId)
            if (polling.isEmpty()) {
                context.stopService(Intent(context, VideoJobService::class.java))
            }
        }
    }

    private fun VideoJobEntity.upstreamIdOrThrow(): String =
        upstreamJobId ?: error("缺少上游任务 id")

    /** queued/running/in_progress/pending → running；completed/success → completed；failed/error → failed */
    private fun normalizeStatus(raw: String): String = when (raw.lowercase()) {
        "completed", "succeeded", "success" -> "completed"
        "failed", "error", "cancelled", "canceled" -> "failed"
        else -> "running"
    }

    private suspend fun completeJob(
        job: VideoJobEntity,
        provider: ProviderEntity,
        apiKey: String,
        headers: Map<String, String>,
        upstreamId: String,
    ) {
        val now = System.currentTimeMillis()
        val bytes = app.providerClient.downloadVideo(provider.baseUrl, apiKey, upstreamId, headers).getOrNull()
        if (bytes == null) {
            failJob(job, "视频下载失败")
            return
        }
        val dir = File(context.filesDir, "videos").apply { mkdirs() }
        val file = File(dir, "flux_video_${job.id}.mp4")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        dao.update(
            job.copy(
                status = VideoJobEntity.STATUS_DONE,
                videoPath = file.absolutePath,
                updatedAt = now,
            ),
        )
        // 落一条只带视频的助手消息（flux-video: 前缀引用文件，与图片同一渲染/清理通道）
        app.database.messageDao().insert(
            MessageEntity(
                conversationId = job.conversationId,
                role = Role.ASSISTANT,
                content = "",
                imagesJson = MessageEntity.imagesJsonOf(listOf(MessageEntity.VIDEO_PREFIX + file.absolutePath)),
                providerId = job.providerId,
                modelName = job.modelName,
            ),
        )
        app.database.conversationDao().touch(job.conversationId, now)
        Notifications.notifyVideoResult(
            context, job.id, job.conversationId,
            success = true, text = "${job.modelName} 已完成，点击查看",
        )
    }

    private suspend fun failJob(job: VideoJobEntity, error: String) {
        dao.update(
            job.copy(
                status = VideoJobEntity.STATUS_FAILED,
                error = error,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Notifications.notifyVideoResult(
            context, job.id, job.conversationId,
            success = false, text = "${job.modelName}：$error",
        )
    }
}

/**
 * 视频任务前台服务：存在活跃任务时保活轮询与通知（仿 LanService 的 dataSync 类型）。
 * 任务清空自动停自身；进程被杀重启后经 [VideoJobManager.resumeAll] 恢复未完成任务。
 */
class VideoJobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = VideoJobManager.get(applicationContext)
        when (intent?.action) {
            ACTION_CANCEL_JOB -> manager.cancel(intent.getLongExtra(EXTRA_JOB_ID, -1L))
            ACTION_KEEP_WAITING -> manager.onKeepWaiting(intent.getLongExtra(EXTRA_JOB_ID, -1L))
        }
        manager.resumeAll()
        if (!manager.hasActiveJobs()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 前台通知取最早一个活跃任务的槽位（轮询循环会按任务持续更新同 ID）
        val first = manager.firstActiveForForeground()
        ServiceCompat.startForeground(
            this, first.first, first.second,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_STICKY
    }

    companion object {
        const val ACTION_CANCEL_JOB = "cn.luoluna.flux.video.CANCEL_JOB"
        const val ACTION_KEEP_WAITING = "cn.luoluna.flux.video.KEEP_WAITING"
        const val EXTRA_JOB_ID = "job_id"

        fun intent(context: Context) = Intent(context, VideoJobService::class.java)
    }
}

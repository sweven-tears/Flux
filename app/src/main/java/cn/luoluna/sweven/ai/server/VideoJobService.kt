package cn.luoluna.sweven.ai.server

import cn.luoluna.sweven.ai.data.db.ref

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.db.VideoJobEntity
import cn.luoluna.sweven.ai.net.ApiException
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import cn.luoluna.sweven.ai.net.video.VideoAdapters
import cn.luoluna.sweven.ai.net.video.VideoApiAdapter
import cn.luoluna.sweven.ai.net.video.VideoRequestContext
import cn.luoluna.sweven.ai.net.video.VideoTask
import cn.luoluna.sweven.ai.net.video.downloadVideoBytes
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** LAN 网关任务的会话哨兵：无聊天会话（客户端轮询获知结果，不落消息不发通知） */
internal const val VIDEO_JOB_NO_CONVERSATION = 0L

/**
 * 视频生成任务管理器（App 级单例）：适配器层编排者。
 * 提交时按模型记忆的接口形态（ModelEntity.videoApiShape）直发，未记忆则按协议提示逐形态探测并
 * **写回模型行终身免探测**；轮询 10s 无硬超时——直到上游终态或用户取消；
 * 每等待满 10 分钟把通知换成「继续等待吗？」询问（2 分钟无操作默认继续），「取消任务」按钮常驻；
 * 完成下载 mp4 存私有目录、落 `flux-video:` 助手消息（LAN 任务 conversationId=0 哨兵除外）。
 * 进程被杀后由服务/App 启动恢复未完成任务。
 */
class VideoJobManager private constructor(private val context: Context) {

    private val app = context.applicationContext as App
    private val dao = app.database.videoJobDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val polling = ConcurrentHashMap<Long, Job>()

    /** jobId → 提交命中的接口形态（重启后从模型行恢复） */
    private val jobShapes = ConcurrentHashMap<Long, String>()

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
     * 提交视频生成任务（App 内聊天与局域网网关共用，挂起等待提交结果）：
     * 探测/复用接口形态 → 落 running 任务 → 启动轮询与前台服务；失败落 failed 行把错误抛回调用方。
     * [extraBody] 为调用方附加参数（局域网请求透传的 size/seconds 等），与模型 customBody 合并（调用方优先）。
     * 成功返回任务行 id（局域网以 `flux-<id>` 对外）。
     */
    suspend fun submit(
        conversationId: Long,
        provider: ProviderEntity,
        apiKey: String,
        model: ModelEntity,
        prompt: String,
        extraBody: JsonObject? = null,
    ): Result<Long> = runCatching {
        val ctx = buildContext(provider, apiKey, model, prompt, extraBody)
        val (shape, upstreamId) = resolveShapeAndSubmit(ctx, model)
        val id = dao.insert(
            VideoJobEntity(
                conversationId = conversationId,
                providerId = provider.id,
                modelName = model.ref,
                prompt = prompt,
                upstreamJobId = upstreamId,
                status = VideoJobEntity.STATUS_RUNNING,
            ),
        )
        jobShapes[id] = shape
        startPolling(id)
        ensureService()
        id
    }.onFailure { e ->
        dao.insert(
            VideoJobEntity(
                conversationId = conversationId,
                providerId = provider.id,
                modelName = model.ref,
                prompt = prompt,
                status = VideoJobEntity.STATUS_FAILED,
                error = e.message ?: "提交失败",
            ),
        )
    }

    /** 探测/复用接口形态并提交：模型已记忆直发；否则按协议提示逐形态试，成功即写回模型行 */
    private suspend fun resolveShapeAndSubmit(
        ctx: VideoRequestContext,
        model: ModelEntity,
    ): Pair<String, String> {
        val preferred = VideoAdapters.byId(model.videoApiShape)
        val order = (listOfNotNull(preferred) + VideoAdapters.probeOrder(ctx.protocol)).distinct()
        var lastError: Throwable? = null
        for (adapter in order) {
            val r = adapter.submit(ctx)
            val key = r.getOrNull()
            if (key != null) {
                if (model.id > 0) {
                    val row = app.database.modelDao().find(model.providerId, model.ref)
                    if (row != null && row.videoApiShape != adapter.shapeId) {
                        app.database.modelDao().update(row.copy(videoApiShape = adapter.shapeId))
                    }
                }
                return adapter.shapeId to key
            }
            lastError = r.exceptionOrNull() ?: lastError
        }
        throw (lastError ?: ApiException("没有可用的视频接口形态"))
    }

    /**
     * 进程/服务重启后恢复未完成任务（提交中断、无上游 id 的行标记失败）。[onDone] 带回是否存在活跃任务。
     * **[startService] 仅进程冷启动（App.onCreate）可传 true**——服务自己的 onStartCommand 路径绝不能再
     * startForegroundService，否则 onStartCommand → resumeAll → ensureService → onStartCommand 自喂成
     * 启动风暴（实测每 0.5s 重发通知、系统 Shedding 丢掉真实更新，面板永远停留在旧文案）。
     */
    fun resumeAll(startService: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        scope.launch {
            var hasJobs = false
            val pending = dao.active()
            pending.forEach { job ->
                if (job.upstreamJobId != null) {
                    startPolling(job.id)
                    hasJobs = true
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
            if (startService) {
                // 进程冷启动：清掉上个会话遗留的任务通知（僵尸占位/旧进度），结果内容已在聊天里
                runCatching {
                    dao.recentTerminal().forEach { Notifications.cancelVideoNotification(context, it.id) }
                    NotificationManagerCompat.from(context).cancel(Notifications.ID_VIDEO_FOREGROUND)
                }
            }
            if (startService && hasJobs) ensureService()
            onDone?.invoke(hasJobs)
        }
    }

    /** 用户取消（通知按钮/局域网客户端不可取消）：停轮询、尽力取消上游、清通知 */
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
            val model = provider?.let { app.database.modelDao().find(it.id, job.modelName) }
            val key = model?.let { app.modelRouter.keyFor(it, provider) }?.key
            val shape = jobShapes[jobId] ?: model?.videoApiShape
            val adapter = VideoAdapters.byId(shape)
            if (provider != null && key != null && job.upstreamJobId != null && adapter != null) {
                // 尽力通知上游取消（不支持取消的形态是 no-op）
                runCatching {
                    adapter.cancel(buildContext(provider, key, model ?: defaultModel(provider, job), job.prompt), job.upstreamJobId)
                }
            }
            if (job.conversationId != VIDEO_JOB_NO_CONVERSATION) {
                Notifications.cancelVideoNotification(context, jobId)
            }
        }
    }

    private fun defaultModel(provider: ProviderEntity, job: VideoJobEntity) =
        ModelEntity(providerId = provider.id, name = job.modelName)

    /** 用户点「继续等待」：复位询问窗并顺延到下一个 10 分钟节点 */
    fun onKeepWaiting(jobId: Long) {
        keepSignals[jobId] = true
    }

    private fun ensureService() {
        // 后台启动 FGS 受系统限制（App 被系统拉起时可能被拒），失败则等下次前台时机再恢复
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, VideoJobService::class.java))
        }
    }

    private fun startPolling(jobId: Long) {
        if (polling.containsKey(jobId)) return
        polling[jobId] = scope.launch { pollLoop(jobId) }
    }

    private fun buildContext(
        provider: ProviderEntity,
        apiKey: String,
        model: ModelEntity,
        prompt: String,
        callExtra: JsonObject? = null,
    ): VideoRequestContext {
        val modelExtra = model.customBody?.takeIf { it.isNotBlank() }?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val merged = when {
            modelExtra == null -> callExtra
            callExtra == null -> modelExtra
            else -> JsonObject(modelExtra + callExtra) // 调用方参数覆盖模型 customBody 同名键
        }
        return VideoRequestContext(
            baseUrl = provider.baseUrl,
            apiKey = apiKey,
            model = model.name,
            headers = parseCustomHeaders(model.customHeaders),
            protocol = provider.protocol,
            prompt = prompt,
            extraBody = merged,
        )
    }

    private suspend fun pollLoop(jobId: Long) {
        try {
            var waitedMs = 0L
            var askedAtMs = -1L
            var postedMinutes = -1
            var postedAsking = false
            while (coroutineContext.isActive) {
                val job = dao.byId(jobId) ?: return
                if (job.status != VideoJobEntity.STATUS_PENDING && job.status != VideoJobEntity.STATUS_RUNNING) {
                    return
                }
                val upstreamId = job.upstreamJobId ?: return
                val provider = app.database.providerDao().byId(job.providerId)
                if (provider == null || !provider.enabled) {
                    failJob(job, "厂商已删除或停用")
                    return
                }
                val model = app.database.modelDao().find(provider.id, job.modelName)
                    ?: ModelEntity(providerId = provider.id, name = job.modelName)
                val key = app.modelRouter.keyFor(model, provider)?.key
                if (key.isNullOrBlank()) {
                    failJob(job, "厂商缺少 API Key")
                    return
                }
                val ctx = buildContext(provider, key, model, job.prompt)
                val shape = jobShapes[jobId] ?: model.videoApiShape?.also { jobShapes[jobId] = it }
                val adapter: VideoApiAdapter = VideoAdapters.byId(shape) ?: run {
                    failJob(job, "缺少视频接口形态（请重新发起任务）")
                    return
                }

                val task: Result<VideoTask> = adapter.query(ctx, upstreamId)
                when {
                    // 任务不存在（404）为终态；其余查询抖动不终止——无硬超时，继续轮询
                    task.isFailure && task.exceptionOrNull() is HttpApiException &&
                        (task.exceptionOrNull() as HttpApiException).httpCode == 404 -> {
                        failJob(job, "上游任务不存在（可能已被清理）")
                        return
                    }
                    task.isSuccess -> {
                        val t = task.getOrThrow()
                        when (t.status) {
                            "completed" -> {
                                completeJob(job, adapter, ctx, t, upstreamId)
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
                // 只在分钟数/询问态变化时刷新通知，避免每轮轮询都重发（观感是「几秒刷一次」）
                val minutes = (waitedMs / 60_000).toInt()
                if (job.conversationId != VIDEO_JOB_NO_CONVERSATION &&
                    (minutes != postedMinutes || asking != postedAsking)
                ) {
                    postedMinutes = minutes
                    postedAsking = asking
                    Notifications.notifyVideoProgress(
                        context, jobId, job.conversationId, job.modelName, minutes, asking,
                    )
                }
            }
        } finally {
            polling.remove(jobId)
            scheduleIdleStop()
        }
    }

    /** 最后一个任务结束后延迟去抖再停服务：避开「完成→立刻又有新提交」的竞态 */
    private fun scheduleIdleStop() {
        scope.launch {
            delay(3_000)
            if (polling.isEmpty() && dao.active().isEmpty()) {
                context.stopService(Intent(context, VideoJobService::class.java))
            }
        }
    }

    private suspend fun completeJob(
        job: VideoJobEntity,
        adapter: VideoApiAdapter,
        ctx: VideoRequestContext,
        task: VideoTask,
        upstreamId: String,
    ) {
        val now = System.currentTimeMillis()
        // 提取到的成品 URL 优先直下（裸下→鉴权重试）；无 URL 回落适配器自己的下载（如 Sora /content）
        val authMode = if (adapter.shapeId == "veo") "goog" else "bearer"
        var bytes: ByteArray? = task.videoUrl?.let { downloadVideoBytes(it, ctx.apiKey, authMode).getOrNull() }
        if (bytes == null) bytes = adapter.download(ctx, upstreamId, task).getOrNull()
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
        if (job.conversationId == VIDEO_JOB_NO_CONVERSATION) return // LAN 任务：客户端轮询，不落消息不发通知
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
        if (job.conversationId == VIDEO_JOB_NO_CONVERSATION) return
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
        // startForegroundService 必须立刻 startForeground（主线程不能碰数据库，先给占位通知）；
        // 前台槽位固定 2005，任务进度/结果各自用 3000+jobId 槽位——服务停止时系统只回收 2005，
        // 「已生成/已失败」通知不受影响（此前与前台同槽会被连带撤掉）
        ServiceCompat.startForeground(
            this, Notifications.ID_VIDEO_FOREGROUND, Notifications.buildVideoForegroundPlaceholder(this),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        manager.resumeAll { hasJobs ->
            if (!hasJobs) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 任何停止路径（含 pollLoop 的空闲 stopService）都把占位通知撤干净，不留僵尸
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL_JOB = "cn.luoluna.flux.video.CANCEL_JOB"
        const val ACTION_KEEP_WAITING = "cn.luoluna.flux.video.KEEP_WAITING"
        const val EXTRA_JOB_ID = "job_id"

        fun intent(context: Context) = Intent(context, VideoJobService::class.java)
    }
}

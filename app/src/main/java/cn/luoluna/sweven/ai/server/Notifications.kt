package cn.luoluna.sweven.ai.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.luoluna.sweven.ai.MainActivity
import cn.luoluna.sweven.ai.R

object Notifications {
    const val CHANNEL_LAN = "lan_server"
    const val CHANNEL_CHAT = "chat_status"
    const val ID_SERVER = 2001
    const val ID_PENDING_DEVICE = 2002
    const val ID_STREAM = 2003

    /** 视频前台服务的占位/聚合槽位（各任务进度在 3000+jobId 槽位） */
    const val ID_VIDEO_FOREGROUND = 2005

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_LAN,
            "局域网聚合路由",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "聚合路由服务运行状态与设备授权提醒" }
        manager.createNotificationChannel(channel)
        val chatChannel = NotificationChannel(
            CHANNEL_CHAT,
            "聊天助手",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "离开聊天页时的回复进度提醒与视频生成通知" }
        manager.createNotificationChannel(chatChannel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun serverNotification(context: Context, text: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_LAN, true)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_LAN)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("聚合路由运行中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun notifyPendingDevice(context: Context, deviceId: String, ip: String) {
        if (!hasPermission(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_LAN, true)
        }
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "新设备申请使用聚合模型"
        val text = "$ip · $deviceId"
        val builder = NotificationCompat.Builder(context, CHANNEL_LAN)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n请在 App「局域网」页面批准或拒绝"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationManagerCompat.from(context).notify(ID_PENDING_DEVICE, builder.build())
    }

    /**
     * 聊天流式状态通知（同一 ID 先后发「正在回复 / 已完成」完成更新）：
     * 进行中 ongoing + setOnlyAlertOnce（首次提醒即可），完成 autoCancel 可点击回聊天页。
     */
    fun notifyChatStatus(context: Context, done: Boolean, conversationId: Long?, text: String) {
        if (!hasPermission(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            if (conversationId != null) putExtra(MainActivity.EXTRA_OPEN_CHAT_CONVERSATION, conversationId)
        }
        val pending = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(if (done) "回复已完成" else "正在回复…")
            .setContentText(text)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (done) {
            builder.setAutoCancel(true)
        } else {
            builder.setOngoing(true).setOnlyAlertOnce(true)
        }
        NotificationManagerCompat.from(context).notify(ID_STREAM, builder.build())
    }

    /** 撤销聊天流式状态通知（回到聊天页时） */
    fun cancelChatStatus(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_STREAM)
    }

    // ---- 视频任务通知（前台服务常驻与完成/失败提醒共用同一槽位，进行中 → 结果原地替换） ----

    fun videoNotificationId(jobId: Long): Int = 3000 + jobId.toInt()

    private fun chatClickPending(context: Context, conversationId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
            if (conversationId != null) putExtra(MainActivity.EXTRA_OPEN_CHAT_CONVERSATION, conversationId)
        }
        // requestCode 掺入会话 id：Intent.filterEquals 不比较 extras，共用码会让多个会话的通知互相覆盖
        return PendingIntent.getActivity(
            context, 3 + (conversationId ?: 0L).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 生成中的任务通知：elapsedMin 为已等待分钟数；asking=true 加「继续等待」按钮（2 分钟询问窗）。「取消任务」常驻 */
    fun notifyVideoProgress(
        context: Context,
        jobId: Long,
        conversationId: Long,
        modelName: String,
        elapsedMin: Int,
        asking: Boolean,
    ) {
        if (!hasPermission(context)) return
        NotificationManagerCompat.from(context).notify(
            videoNotificationId(jobId),
            buildVideoProgress(context, jobId, conversationId, modelName, elapsedMin, asking),
        )
    }

    fun buildVideoProgress(
        context: Context,
        jobId: Long,
        conversationId: Long,
        modelName: String,
        elapsedMin: Int,
        asking: Boolean,
    ): Notification {
        fun serviceAction(action: String, slot: Int): PendingIntent {
            val intent = Intent(context, VideoJobService::class.java).apply {
                this.action = action
                putExtra(VideoJobService.EXTRA_JOB_ID, jobId)
            }
            // requestCode 掺入 jobId：同 action 不同任务必须是不同 PendingIntent，否则 extras 互相覆盖
            return PendingIntent.getService(
                context, (jobId.toInt() shl 2) or slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(
                if (asking) "视频仍在生成，继续等待吗？" else "视频生成中（已等待 $elapsedMin 分钟）",
            )
            .setContentText("$modelName · 点按通知可回到会话")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(chatClickPending(context, conversationId))
            .addAction(0, "取消任务", serviceAction(VideoJobService.ACTION_CANCEL_JOB, 1))
        if (asking) {
            builder.addAction(0, "继续等待", serviceAction(VideoJobService.ACTION_KEEP_WAITING, 2))
        }
        return builder.build()
    }

    /** 完成/失败提醒：autoCancel，点击回对应会话（槽位与进行中相同，原地替换） */
    fun notifyVideoResult(
        context: Context,
        jobId: Long,
        conversationId: Long,
        success: Boolean,
        text: String,
    ) {
        if (!hasPermission(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(if (success) "视频已生成" else "视频生成失败")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(chatClickPending(context, conversationId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        NotificationManagerCompat.from(context).notify(videoNotificationId(jobId), builder.build())
    }

    fun cancelVideoNotification(context: Context, jobId: Long) {
        NotificationManagerCompat.from(context).cancel(videoNotificationId(jobId))
    }

    /** 前台服务启动瞬间的占位通知（必须在主线程同步给出，任务详情随后异步替换） */
    fun buildVideoForegroundPlaceholder(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("视频任务服务运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}

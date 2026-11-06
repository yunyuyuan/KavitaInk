package com.manus.kavitaink.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.pdf.PdfRenderer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import com.manus.kavitaink.App
import com.manus.kavitaink.R
import com.manus.kavitaink.core.Downloader
import com.manus.kavitaink.core.KavitaApi
import com.manus.kavitaink.ui.DownloadsActivity
import java.io.File

/**
 * 前台下载服务：只在有排队任务时运行；全部完成或全部暂停后立即停止并释放唤醒锁 / Wi-Fi 锁。
 * 应用没有任何其他后台任务（无同步、无定时器、无推送）。
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val NOTIF_STATUS = 2

        /** 网络中断后的自动重试间隔（断点续传，重试代价很小） */
        private val RETRY_DELAYS = longArrayOf(5_000, 15_000, 45_000)

        fun start(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
    }

    private val lock = Any()
    private var running = false
    @Volatile private var lastStartId = 0
    private var lastNotifAt = 0L

    private val app get() = application as App
    private val nm get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            lastStartId = startId
            nm.cancel(NOTIF_STATUS)
            goForeground(build("准备下载…", "", -1))
            if (!running) {
                running = true
                Thread({ loop() }, "kavita-download").start()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun loop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KavitaInk:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "KavitaInk:download").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            while (true) {
                val e = synchronized(lock) {
                    val n = app.queue.takeNext()
                    if (n == null) running = false
                    n
                } ?: break
                val api: KavitaApi? = app.prefs.api()
                if (api == null) {
                    app.queue.markFailed(e, "未登录服务器")
                    continue
                }
                notifyProgress(e, force = true)
                runEntry(api, e)
            }
        } finally {
            runCatching { wake.release() }
            runCatching { wifi.release() }
            synchronized(lock) {
                if (!running) {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf(lastStartId)
                    postStatus()
                }
            }
        }
    }

    /** 下载一项；网络类错误自动重试几次（每次都从断点继续） */
    private fun runEntry(api: KavitaApi, e: DownloadQueue.Entry) {
        var attempt = 0
        while (true) {
            val dl = Downloader(api, app.storage)
            e.abort = { dl.abort() }
            // 设置 abort 之前用户可能已经点了暂停
            if (e.stop != DownloadQueue.Stop.NONE) dl.abort()
            try {
                dl.download(
                    e.task,
                    { done, total, pages ->
                        app.queue.progress(e, done, total, pages)
                        notifyProgress(e, force = false)
                    },
                    { e.stop != DownloadQueue.Stop.NONE },
                    ::pdfRenderable,
                )
                app.queue.markDone(e)
                return
            } catch (c: Downloader.Cancelled) {
                app.queue.finishStopped(e)
                return
            } catch (t: Throwable) {
                if (e.stop != DownloadQueue.Stop.NONE) {
                    app.queue.finishStopped(e)
                    return
                }
                if (attempt < RETRY_DELAYS.size && KavitaApi.isTransient(t)) {
                    val delay = RETRY_DELAYS[attempt++]
                    app.queue.note(e, "${KavitaApi.friendlyError(t)}，${delay / 1000} 秒后自动重试（第 $attempt 次）")
                    notifyProgress(e, force = true)
                    if (!sleepUnlessStopped(e, delay)) {
                        app.queue.finishStopped(e)
                        return
                    }
                    app.queue.note(e, "")
                    continue
                }
                app.queue.markFailed(e, KavitaApi.friendlyError(t))
                return
            }
        }
    }

    /** 等待重试；期间若被暂停 / 取消则返回 false */
    private fun sleepUnlessStopped(e: DownloadQueue.Entry, ms: Long): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (e.stop != DownloadQueue.Stop.NONE) return false
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return e.stop == DownloadQueue.Stop.NONE
    }

    /** 服务停止时：如果有失败或暂停的任务，留一条可划掉的通知 */
    private fun postStatus() {
        val failed = app.queue.count(DownloadQueue.State.FAILED)
        val paused = app.queue.count(DownloadQueue.State.PAUSED)
        if (failed == 0 && paused == 0) return
        val title = when {
            failed > 0 && paused > 0 -> "$failed 项下载失败，$paused 项已暂停"
            failed > 0 -> "有 $failed 项下载失败"
            else -> "已暂停 $paused 项下载"
        }
        val text = if (failed > 0) "点击查看并重试，已下载的部分会保留" else "已下载的部分已保留，点击「全部继续」接着下载"
        val n = build(title, text, -1, ongoing = false, action = "全部继续" to DownloadActionReceiver.RESUME_ALL)
        nm.notify(NOTIF_STATUS, n)
    }

    private fun notifyProgress(e: DownloadQueue.Entry, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastNotifAt < 2500) return
        lastNotifAt = now
        val left = app.queue.pendingCount()
        val detail = DownloadQueue.formatProgress(e)
        val text = buildString {
            append(e.task.seriesName).append(" · ").append(e.task.label)
            if (detail.isNotBlank()) append(" · ").append(detail)
            if (e.note.isNotBlank()) append(" · ").append(e.note)
        }
        nm.notify(
            NOTIF_ID,
            build("正在下载（剩余 $left 项）", text, if (e.total > 0) e.percent else -1, action = "全部暂停" to DownloadActionReceiver.PAUSE_ALL)
        )
    }

    @Suppress("DEPRECATION")
    private fun build(
        title: String,
        text: String,
        percent: Int,
        ongoing: Boolean = true,
        action: Pair<String, String>? = null,
    ): Notification {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, DownloadsActivity::class.java), flags)
        b.setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 16) b.setStyle(Notification.BigTextStyle().bigText(text))
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)
        if (percent >= 0) b.setProgress(100, percent, false)
        if (action != null) {
            val ai = Intent(this, DownloadActionReceiver::class.java).setAction(action.second)
            val api = PendingIntent.getBroadcast(this, action.second.hashCode(), ai, flags)
            b.addAction(R.drawable.ic_stat_download, action.first, api)
        }
        return b.build()
    }

    /** 本机 PdfRenderer 能否打开该 PDF（加密或损坏的 PDF 会改为下载页面图片） */
    private fun pdfRenderable(f: File): Boolean = try {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(pfd)
            try {
                r.pageCount > 0
            } finally {
                r.close()
            }
        } finally {
            runCatching { pfd.close() }
        }
    } catch (t: Throwable) {
        false
    }
}

/** 通知栏按钮：全部暂停 / 全部继续 */
class DownloadActionReceiver : BroadcastReceiver() {
    companion object {
        const val PAUSE_ALL = "com.manus.kavitaink.action.PAUSE_ALL"
        const val RESUME_ALL = "com.manus.kavitaink.action.RESUME_ALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        when (intent.action) {
            PAUSE_ALL -> app.queue.pauseAll()
            RESUME_ALL -> {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
                app.queue.resumeAll()
            }
        }
    }
}

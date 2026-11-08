package net.yunyuyuan.kavitaink.download

import net.yunyuyuan.kavitaink.core.error
import net.yunyuyuan.kavitaink.texts
import net.yunyuyuan.kavitaink.Lang
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
import net.yunyuyuan.kavitaink.App
import net.yunyuyuan.kavitaink.R
import net.yunyuyuan.kavitaink.core.Downloader
import net.yunyuyuan.kavitaink.core.KavitaApi
import net.yunyuyuan.kavitaink.core.LibraryCache
import net.yunyuyuan.kavitaink.StorageAccess
import net.yunyuyuan.kavitaink.ui.DownloadsActivity
import java.io.File

/**
 * 前台下载服务：只在有排队任务时运行；全部完成或全部暂停后立即停止并释放唤醒锁 / Wi-Fi 锁。
 * 应用没有任何其他后台任务（无同步、无定时器、无推送）。
 *
 * 并发：最多 [Prefs.downloadConcurrency] 个工作线程，各自从队列里取下一项，所有格式都一样；
 * 逐页下载的章节在章节内部也会同时下载几页。
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
    /** 正在运行的工作线程数（受 lock 保护） */
    private var workers = 0
    private var workerSeq = 0
    @Volatile private var lastStartId = 0
    private var lastNotifAt = 0L
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    /** 本次下载会话共用的书库缓存（所有工作线程共用，换服务器 / 重新登录后重建） */
    private var libs: Pair<KavitaApi, LibraryCache>? = null

    private val app get() = application as App
    private val nm get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            lastStartId = startId
            nm.cancel(NOTIF_STATUS)
            goForeground(build(getString(R.string.notif_preparing), "", -1))
            if (workers == 0) acquireLocks()
            // 设置里调大了同时下载数，或有新任务：补足工作线程（多出来的线程取不到任务会自己退出）。
            // 按「正在下载 + 排队中」计算：已有一项在下载时再加一项，也要马上开第二个线程
            val active = app.queue.count(DownloadQueue.State.RUNNING) + app.queue.count(DownloadQueue.State.QUEUED)
            val want = app.prefs.downloadConcurrency.coerceAtMost(maxOf(1, active))
            while (workers < want) {
                workers++
                val n = ++workerSeq
                Thread({ loop() }, "kavita-download-$n").start()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KavitaInk:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "KavitaInk:download").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseLocks() {
        wake?.let { runCatching { it.release() } }
        wifi?.let { runCatching { it.release() } }
        wake = null
        wifi = null
    }

    private fun libraryCache(api: KavitaApi): LibraryCache = synchronized(lock) {
        val cur = libs
        if (cur != null && cur.first.base == api.base && cur.first.apiKey == api.apiKey) return cur.second
        LibraryCache(api).also { libs = api to it }
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun loop() {
        while (true) {
            val e = synchronized(lock) {
                // 设置里调小了同时下载数：多出来的线程做完手上的就退出
                val n = if (workers > app.prefs.downloadConcurrency) null else app.queue.takeNext()
                if (n == null) {
                    workers--
                    if (workers == 0) finishSession()
                }
                n
            } ?: return
            val api: KavitaApi? = app.prefs.api()
            if (api == null) {
                app.queue.markFailed(e, getString(R.string.err_not_logged_in))
                continue
            }
            if (!StorageAccess.granted(this)) {
                app.queue.markFailed(e, getString(net.yunyuyuan.kavitaink.StorageAccess.deniedText()))
                continue
            }
            notifyProgress(force = true)
            runEntry(api, e)
            notifyProgress(force = true)
        }
    }

    /** 最后一个工作线程退出时调用（持有 lock） */
    private fun finishSession() {
        releaseLocks()
        libs = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf(lastStartId)
        postStatus()
    }

    /** 下载一项；网络类错误自动重试几次（每次都从断点继续） */
    private fun runEntry(api: KavitaApi, e: DownloadQueue.Entry) {
        var attempt = 0
        var mkdirRetry = 0
        while (true) {
            val dl = Downloader(api, app.storage, libraryCache(api), app.prefs.downloadConcurrency)
            e.abort = { dl.abort() }
            // 设置 abort 之前用户可能已经点了暂停
            if (e.stop != DownloadQueue.Stop.NONE) dl.abort()
            try {
                dl.download(
                    e.task,
                    { done, total, pages ->
                        app.queue.progress(e, done, total, pages)
                        notifyProgress(force = false)
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
                if (t is net.yunyuyuan.kavitaink.core.StorageException && t.res == R.string.err_mkdir_content &&
                    mkdirRetry < 3 && StorageAccess.granted(this)
                ) {
                    // 刚授予存储权限时，系统要过一会儿才把本进程的存储重新挂载为可写（个别系统较慢）：稍等再试
                    mkdirRetry++
                    if (!sleepUnlessStopped(e, 2_000L * mkdirRetry)) {
                        app.queue.finishStopped(e)
                        return
                    }
                    continue
                }
                if (attempt < RETRY_DELAYS.size && KavitaApi.isTransient(t)) {
                    val delay = RETRY_DELAYS[attempt++]
                    app.queue.note(e, getString(R.string.dl_retry_note, texts().error(t), (delay / 1000).toInt(), attempt))
                    notifyProgress(force = true)
                    if (!sleepUnlessStopped(e, delay)) {
                        app.queue.finishStopped(e)
                        return
                    }
                    app.queue.note(e, "")
                    continue
                }
                app.queue.markFailed(e, texts().error(t))
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
        val x = texts()
        val f = x.p(R.plurals.notif_failed_n, failed)
        val pz = x.p(R.plurals.notif_paused_n, paused)
        val title = when {
            failed > 0 && paused > 0 -> x.s(R.string.notif_join, f, pz)
            failed > 0 -> f
            else -> pz
        }
        val text = x.s(if (failed > 0) R.string.notif_tap_retry else R.string.notif_tap_resume)
        val n = build(title, text, -1, ongoing = false, action = x.s(R.string.action_resume_all) to DownloadActionReceiver.RESUME_ALL)
        nm.notify(NOTIF_STATUS, n)
    }

    /** 通知栏：同时下载几项时每项一行，进度条显示它们的平均进度 */
    private fun notifyProgress(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            if (!force && now - lastNotifAt < 2500) return
            if (workers == 0) return
            lastNotifAt = now
        }
        val running = app.queue.snapshot().filter { it.state == DownloadQueue.State.RUNNING }
        if (running.isEmpty()) return
        val left = app.queue.pendingCount()
        val x = texts()
        val lines = running.map { e ->
            buildString {
                append(e.task.seriesName).append(" · ").append(e.task.title(x))
                val detail = DownloadQueue.formatProgress(e, x)
                if (detail.isNotBlank()) append(" · ").append(detail)
                if (e.note.isNotBlank()) append(" · ").append(e.note)
            }
        }
        val known = running.filter { it.total > 0 }
        val percent = if (known.isEmpty()) -1 else known.sumOf { it.percent } / known.size
        val n = build(
            x.p(R.plurals.notif_downloading_left, left), lines.joinToString("\n"), percent,
            action = x.s(R.string.action_pause_all) to DownloadActionReceiver.PAUSE_ALL,
        )
        synchronized(lock) {
            // 最后一个线程可能刚结束会话：不要再把前台通知发出来
            if (workers > 0) nm.notify(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun build(
        title: String,
        text: String,
        percent: Int,
        ongoing: Boolean = true,
        action: Pair<String, String>? = null,
    ): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            // 每次都创建：同一 ID 重复创建只会更新名称，切换语言后渠道名也会跟着变
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
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
        const val PAUSE_ALL = "net.yunyuyuan.kavitaink.action.PAUSE_ALL"
        const val RESUME_ALL = "net.yunyuyuan.kavitaink.action.RESUME_ALL"
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

package com.manus.kavitaink.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.manus.kavitaink.R
import com.manus.kavitaink.core.Downloader
import com.manus.kavitaink.core.Updater
import com.manus.kavitaink.core.error
import com.manus.kavitaink.core.formatBytes
import com.manus.kavitaink.update.ApkProvider
import java.io.File

/**
 * 检查更新：只在打开这个页面时访问 GitHub；下载可取消、可续传；下载完交给系统安装器。
 * 没有任何后台检查或定时任务。
 */
class UpdateActivity : BaseActivity() {

    companion object {
        private const val REQ_INSTALL_PERM = 51
    }

    private lateinit var status: TextView
    private lateinit var notesTitle: TextView
    private lateinit var notes: TextView
    private lateinit var mainBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var pageBtn: Button

    private var release: Updater.Release? = null
    private var apk: File? = null
    private var downloader: Downloader? = null
    @Volatile private var stop = false
    private var busy = false
    private var mainAction: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        root.addView(header(getString(R.string.update_check), back = true).first)
        val scroll = ScrollView(this).apply { isVerticalFadingEdgeEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val box = vbox().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        box.addView(label(getString(R.string.update_current, currentVersion()), 15f, bold = true))
        status = label("", 15f).apply { setPadding(0, dp(10), 0, dp(10)) }
        box.addView(status)
        notesTitle = label(getString(R.string.update_notes_title), 15f, bold = true).apply { visibility = View.GONE }
        box.addView(notesTitle)
        notes = label("", 14f, color = 0xFF222222.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        box.addView(notes)
        scroll.addView(box)
        root.addView(scroll, lp(MATCH, 0, 1f))

        val bar = hbox().apply { setPadding(dp(8), dp(6), dp(8), dp(8)) }
        mainBtn = button("") { if (!busy) mainAction() }
        cancelBtn = button(getString(R.string.cancel)) { cancel() }.apply { visibility = View.GONE }
        pageBtn = button(getString(R.string.update_open_page)) { openPage() }
        bar.addView(mainBtn, lp(0, WRAP, 1f))
        bar.addView(cancelBtn, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        bar.addView(pageBtn, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        root.addView(hline())
        root.addView(bar)
        setContentView(root)
        check()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 离开页面即停止下载；已下载的部分保留，下次续传
        cancel()
    }

    private fun currentVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun setMain(text: Int, action: () -> Unit) {
        mainBtn.text = getString(text)
        mainAction = action
    }

    private fun check() {
        busy = true
        status.text = getString(R.string.update_checking)
        bg({ Updater.latest() }) { r, err ->
            busy = false
            if (r == null) {
                status.text = getString(R.string.update_failed, x.error(err))
                setMain(R.string.retry, ::check)
                return@bg
            }
            release = r
            val text = Updater.plainNotes(r.notes)
            notes.text = text
            notesTitle.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            if (Updater.isNewer(r.version, currentVersion())) {
                val size = if (r.apkSize > 0) formatBytes(r.apkSize) else getString(R.string.kfonts_size_unknown)
                status.text = getString(R.string.update_found, r.version, size)
                setMain(R.string.update_download, ::download)
            } else {
                status.text = getString(R.string.update_latest, r.version)
                setMain(R.string.update_recheck, ::check)
            }
        }
    }

    private fun download() {
        val r = release ?: return
        busy = true
        stop = false
        cancelBtn.visibility = View.VISIBLE
        var lastUi = 0L
        val total = if (r.apkSize > 0) formatBytes(r.apkSize) else "…"
        status.text = getString(R.string.update_downloading, formatBytes(0).ifBlank { "0 KB" }, total)
        bg({
            Updater.download(r, ApkProvider.dir(this), { done, _, _ ->
                // 墨水屏：进度最多每秒刷新一次
                val now = SystemClock.uptimeMillis()
                if (now - lastUi >= 1000) {
                    lastUi = now
                    runOnUiThread {
                        if (!isFinishing) status.text = getString(R.string.update_downloading, formatBytes(done).ifBlank { "0 KB" }, total)
                    }
                }
            }, { stop }, { downloader = it })
        }) { f, err ->
            busy = false
            downloader = null
            cancelBtn.visibility = View.GONE
            if (isFinishing || isDestroyed) return@bg
            when {
                f != null -> {
                    apk = f
                    verifyAndInstall(f)
                }
                err is Downloader.Cancelled || stop -> {
                    status.text = getString(R.string.update_cancelled)
                    setMain(R.string.update_download, ::download)
                }
                else -> {
                    status.text = getString(R.string.update_dl_failed, x.error(err))
                    setMain(R.string.retry, ::download)
                }
            }
        }
    }

    private fun cancel() {
        stop = true
        downloader?.abort()
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): Set<String>? =
        info?.signatures?.map { it.toCharsString() }?.toSet()?.takeIf { it.isNotEmpty() }

    /** 安装前核对：必须是本应用的安装包；签名不同时提醒（系统会拒绝覆盖安装） */
    private fun verifyAndInstall(f: File) {
        @Suppress("DEPRECATION")
        val archive = runCatching { packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES) }.getOrNull()
        if (archive == null || archive.packageName != packageName) {
            f.delete()
            status.text = getString(R.string.update_bad_package)
            setMain(R.string.update_download, ::download)
            return
        }
        setMain(R.string.update_install) { install(f) }
        @Suppress("DEPRECATION")
        val mine = signatures(runCatching { packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES) }.getOrNull())
        val theirs = signatures(archive)
        if (mine != null && theirs != null && mine != theirs) {
            status.text = getString(R.string.update_sig_mismatch)
            AlertDialog.Builder(this)
                .setTitle(R.string.update_sig_title)
                .setMessage(R.string.update_sig_mismatch)
                .setPositiveButton(R.string.update_install_anyway) { _, _ -> install(f) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        install(f)
    }

    private fun install(f: File) {
        if (!f.isFile) {
            setMain(R.string.update_download, ::download)
            return
        }
        // Android 8+：需要用户允许本应用「安装未知应用」
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.update_perm_title)
                .setMessage(R.string.update_perm_msg)
                .setPositiveButton(R.string.update_perm_go) { _, _ ->
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                            REQ_INSTALL_PERM,
                        )
                    } catch (_: ActivityNotFoundException) {
                        status.text = getString(R.string.update_perm_denied)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 24) {
            intent.setDataAndType(ApkProvider.uriFor(this, f), ApkProvider.MIME)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            // Android 7 以下的安装器只认 file://，需要它能读到文件
            f.setReadable(true, false)
            f.parentFile?.setExecutable(true, false)
            f.parentFile?.setReadable(true, false)
            intent.setDataAndType(Uri.fromFile(f), ApkProvider.MIME)
        }
        try {
            startActivity(intent)
            status.text = getString(R.string.update_installing)
        } catch (_: ActivityNotFoundException) {
            status.text = getString(R.string.update_no_installer)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_INSTALL_PERM) return
        val f = apk ?: return
        if (Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) install(f)
        else status.text = getString(R.string.update_perm_denied)
    }

    private fun openPage() {
        val url = release?.page ?: Updater.RELEASES_PAGE
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            status.text = getString(R.string.update_no_browser, url)
        }
    }
}

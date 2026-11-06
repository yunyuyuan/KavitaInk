package com.manus.kavitaink.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.manus.kavitaink.R
import com.manus.kavitaink.core.Downloader
import com.manus.kavitaink.core.KavitaFonts
import com.manus.kavitaink.core.error
import com.manus.kavitaink.core.formatBytes

/**
 * Kavita 服务器上的字体：按字体族列出，已下载的打 ✓。点一项先查询文件大小，确认后下载该字体族的全部变体。
 * 只在打开这个页面时联网；下载可取消，未完成的部分保留，下次从断点继续。
 */
class KavitaFontsActivity : BaseActivity() {

    private data class Row(val fam: KavitaFonts.RemoteFamily, val installed: Boolean)

    private lateinit var adapter: RowAdapter<Row>
    private lateinit var status: TextView
    private var cancelBtn: Button? = null
    private var kf: KavitaFonts? = null
    @Volatile private var stop = false
    private var downloader: Downloader? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = prefs.api()
        if (api == null) {
            finish()
            return
        }
        kf = KavitaFonts(api)
        val root = root()
        val (head, btns) = header(getString(R.string.kfonts_title), back = true, actions = listOf(getString(R.string.cancel) to ::cancel))
        cancelBtn = btns.firstOrNull()?.apply { visibility = View.GONE }
        root.addView(head)
        status = statusLine()
        root.addView(status)
        val lv = einkList()
        pagedList = lv
        adapter = RowAdapter(emptyList(), { createRow() }, { v, r, _ -> bindRow(v, r) })
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ -> tapped(adapter.items[pos]) }
        root.addView(lv, lp(MATCH, 0, 1f))
        root.addView(pagerBar(lv))
        setContentView(root)
        load()
    }

    private fun createRow(): View {
        val box = vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minimumHeight = dp(56)
        }
        val title = label("", 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = label("", 13f, color = 0xFF333333.toInt())
        box.addView(title)
        box.addView(sub)
        box.tag = arrayOf(title, sub)
        return box
    }

    private fun bindRow(v: View, r: Row) {
        val (title, sub) = tags(v)
        title.text = if (r.installed) "✓ ${r.fam.family}" else r.fam.family
        val parts = ArrayList<String>()
        parts += getString(if (r.fam.userUploaded) R.string.kfonts_user else R.string.kfonts_builtin)
        parts += resources.getQuantityString(R.plurals.fonts_variants, r.fam.fonts.size, r.fam.fonts.size)
        if (r.installed) parts += getString(R.string.kfonts_installed)
        sub.text = parts.joinToString(" · ")
        v.setBackgroundColor(Color.WHITE)
    }

    private fun load() {
        status.text = getString(R.string.loading)
        val k = kf ?: return
        bg({ k.list() }) { list, err ->
            if (list == null) {
                status.text = getString(R.string.kfonts_load_failed, x.error(err))
                return@bg
            }
            show(list)
            status.text = if (list.isEmpty()) getString(R.string.kfonts_empty) else getString(R.string.kfonts_hint)
        }
    }

    private fun show(list: List<KavitaFonts.RemoteFamily>) {
        val have = app.fonts.list().map { it.id }.toSet()
        adapter.update(list.map { Row(it, it.localId in have) })
    }

    private fun tapped(r: Row) {
        if (busy) return
        val k = kf ?: return
        busy = true
        status.text = getString(R.string.kfonts_checking, r.fam.family)
        bg({ r.fam.fonts.map { runCatching { k.size(it) }.getOrDefault(-1L) } }) { sizes, err ->
            busy = false
            if (sizes == null) {
                status.text = getString(R.string.kfonts_failed, x.error(err))
                return@bg
            }
            val known = sizes.filter { it > 0 }
            val size = if (known.size == sizes.size) formatBytes(known.sum()) else getString(R.string.kfonts_size_unknown)
            val n = r.fam.fonts.size
            val msg = resources.getQuantityString(R.plurals.kfonts_confirm, n, r.fam.family, n, size)
            status.text = getString(R.string.kfonts_hint)
            confirm(getString(if (r.installed) R.string.kfonts_redownload_title else R.string.kfonts_download_title), msg, getString(R.string.kfonts_download)) {
                download(r, sizes)
            }
        }
    }

    private fun download(r: Row, sizes: List<Long>) {
        val k = kf ?: return
        val api = prefs.api() ?: return
        busy = true
        stop = false
        cancelBtn?.visibility = View.VISIBLE
        val d = Downloader(api, app.storage)
        downloader = d
        var lastUi = 0L
        status.text = getString(R.string.kfonts_downloading, r.fam.family, formatBytes(0), "…")
        bg({
            k.download(r.fam, sizes, app.fonts, d, { done, total ->
                // 墨水屏：进度最多每秒刷新一次
                val now = SystemClock.uptimeMillis()
                if (now - lastUi >= 1000) {
                    lastUi = now
                    runOnUiThread {
                        if (!isFinishing) status.text = getString(
                            R.string.kfonts_downloading, r.fam.family, formatBytes(done),
                            if (total > 0) formatBytes(total) else "…",
                        )
                    }
                }
            }, { stop })
        }) { f, err ->
            busy = false
            downloader = null
            cancelBtn?.visibility = View.GONE
            if (isFinishing || isDestroyed) return@bg
            when {
                f != null -> {
                    status.text = getString(R.string.kfonts_done, f.name)
                    setResult(RESULT_OK)
                    adapter.update(adapter.items.map { if (it.fam.localId == f.id) it.copy(installed = true) else it })
                }
                err is Downloader.Cancelled || stop -> status.text = getString(R.string.kfonts_cancelled)
                else -> status.text = getString(R.string.kfonts_failed, x.error(err))
            }
        }
    }

    private fun cancel() {
        stop = true
        downloader?.abort()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 离开页面即停止下载；已下载的部分保留，下次续传
        cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun tags(v: android.view.View) = v.tag as Array<TextView>
}

package com.manus.kavitaink.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.R
import com.manus.kavitaink.core.LocalSeries
import com.manus.kavitaink.core.formatBytes
import com.manus.kavitaink.download.DownloadService

/** 书架：只扫描本地目录，打开应用不会产生任何网络请求 */
class MainActivity : BaseActivity() {

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var dlBtn: Button
    private lateinit var adapter: RowAdapter<LocalSeries>
    private lateinit var sel: MultiSelect<LocalSeries, String>
    private var coversShown = true
    private val queueListener: () -> Unit = { updateDownloadButton() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        val (head, btns) = header(
            getString(R.string.shelf_title), back = false, actions = listOf(
                getString(R.string.shelf_libraries) to ::openLibrary,
                getString(R.string.shelf_downloads) to { startActivity(Intent(this, DownloadsActivity::class.java)) },
                getString(R.string.sel_select) to { if (adapter.items.isNotEmpty()) sel.enter() },
                getString(R.string.shelf_settings) to { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        )
        dlBtn = btns[1]
        list = einkList()
        coversShown = prefs.showCovers
        adapter = makeAdapter()
        list.adapter = adapter
        sel = MultiSelect(this, list, { adapter }, { "${it.serverKey}:${it.seriesId}" })
        sel.build(head, listOf(MultiSelect.Op(R.string.sel_delete_n) { askDeleteSelected() }))
        root.addView(head)
        root.addView(sel.head)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list.setOnItemClickListener { _, _, pos, _ ->
            if (sel.active) sel.toggle(pos) else adapter.items.getOrNull(pos)?.let(::open)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (sel.active) sel.toggle(pos) else sel.enter(adapter.items.getOrNull(pos))
            true
        }
        root.addView(list, lp(MATCH, 0, 1f))
        root.addView(sel.bottom)
        root.addView(pagerBar(list))
        setContentView(root)
        pagedList = list

        // 上次未完成的下载：继续
        if (app.queue.pendingCount() > 0) DownloadService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (coversShown != prefs.showCovers) {
            coversShown = prefs.showCovers
            adapter = makeAdapter()
            list.adapter = adapter
        }
        app.queue.addListener(queueListener)
        updateDownloadButton()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        app.queue.removeListener(queueListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sel.active) sel.exit() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun makeAdapter() = RowAdapter<LocalSeries>(
        emptyList(),
        { TwoLineRow(this, prefs.showCovers).root },
        { v, s, _ ->
            val row = TwoLineRow.of(v)
            row.title.text = sel.mark(s) + s.name
            val n = s.chapters.size
            val sb = StringBuilder(resources.getQuantityString(R.plurals.shelf_items, n, n))
            val last = prefs.lastChapter(s.serverKey, s.seriesId)
            s.chapters.firstOrNull { it.chapterId == last }?.let { c ->
                val pct = prefs.percent(c.serverKey, c.chapterId)
                sb.append(" · ").append(
                    if (pct >= 0) getString(R.string.shelf_read_to_pct, c.title(x), pct) else getString(R.string.shelf_read_to, c.title(x))
                )
            }
            row.sub.text = sb
            row.cover?.let { CoverLoader.bind(it, "local:${s.serverKey}:${s.seriesId}", s.cover, null, null) }
        },
    )

    private fun refresh() {
        bg({
            val shelf = app.storage.listShelf().sortedWith(
                compareByDescending<LocalSeries> { prefs.lastRead(it.serverKey, it.seriesId) }.thenBy { it.name }
            )
            shelf to app.storage.usedBytes()
        }) { r, _ ->
            val items = r?.first ?: emptyList()
            adapter.update(items)
            sel.prune()
            if (items.isEmpty()) sel.exit()
            status.text = when {
                items.isNotEmpty() -> resources.getQuantityString(
                    R.plurals.shelf_status, items.size, items.size, formatBytes(r!!.second).ifBlank { "0 KB" },
                )
                app.api() == null -> getString(R.string.shelf_not_connected)
                else -> getString(R.string.shelf_empty)
            }
        }
    }

    private fun updateDownloadButton() {
        val n = app.queue.size()
        dlBtn.text = if (n > 0) getString(R.string.shelf_downloads_n, n) else getString(R.string.shelf_downloads)
    }

    private fun open(s: LocalSeries) {
        SeriesActivity.open(this, s.serverKey, s.seriesId, s.name, s.format, online = false)
    }

    /** 批量删除整部作品的本地内容：先算出将释放的空间再确认 */
    private fun askDeleteSelected() {
        val items = sel.selectedItems()
        if (items.isEmpty()) return
        bg({ items.sumOf { app.storage.sizeOf(it) } }) { bytes, _ ->
            val n = items.size
            val msg = if (n == 1) getString(R.string.shelf_delete_one_msg, items[0].name, formatBytes(bytes ?: 0).ifBlank { "0 KB" })
            else resources.getQuantityString(R.plurals.shelf_delete_msg, n, n, formatBytes(bytes ?: 0).ifBlank { "0 KB" })
            confirm(getString(R.string.shelf_delete_title), msg, getString(R.string.delete)) {
                bg({ items.forEach { app.storage.deleteSeries(it) } }) { _, _ ->
                    sel.exit()
                    refresh()
                }
            }
        }
    }

    private fun openLibrary() {
        if (app.api() == null) startActivity(Intent(this, LoginActivity::class.java))
        else startActivity(Intent(this, LibrariesActivity::class.java))
    }
}

/** Android 13+ 首次下载时请求通知权限（用于显示下载进度），拒绝也不影响下载 */
fun BaseActivity.ensureNotificationPermission() {
    val p = com.manus.kavitaink.App.I.prefs
    if (Build.VERSION.SDK_INT >= 33 && !p.notifAsked) {
        p.notifAsked = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}

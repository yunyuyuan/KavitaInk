package com.manus.kavitaink.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.core.LocalSeries
import com.manus.kavitaink.core.formatBytes
import com.manus.kavitaink.download.DownloadService

/** 书架：只扫描本地目录，打开应用不会产生任何网络请求 */
class MainActivity : BaseActivity() {

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var dlBtn: Button
    private lateinit var adapter: RowAdapter<LocalSeries>
    private var coversShown = true
    private val queueListener: () -> Unit = { updateDownloadButton() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        val (head, btns) = header(
            "书架", back = false, actions = listOf(
                "书库" to ::openLibrary,
                "下载" to { startActivity(Intent(this, DownloadsActivity::class.java)) },
                "设置" to { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        )
        dlBtn = btns[1]
        root.addView(head)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list = einkList()
        coversShown = prefs.showCovers
        adapter = makeAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> adapter.items.getOrNull(pos)?.let(::open) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.let(::askDelete)
            true
        }
        root.addView(list, lp(MATCH, 0, 1f))
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

    private fun makeAdapter() = RowAdapter<LocalSeries>(
        emptyList(),
        { TwoLineRow(this, prefs.showCovers).root },
        { v, s, _ ->
            val row = TwoLineRow.of(v)
            row.title.text = s.name
            val sb = StringBuilder("${s.chapters.size} 项已下载")
            val last = prefs.lastChapter(s.serverKey, s.seriesId)
            s.chapters.firstOrNull { it.chapterId == last }?.let { c ->
                val pct = prefs.percent(c.serverKey, c.chapterId)
                sb.append(" · 读到 ").append(c.label)
                if (pct >= 0) sb.append(" ").append(pct).append('%')
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
            status.text = when {
                items.isNotEmpty() -> "共 ${items.size} 部 · 占用 ${formatBytes(r!!.second).ifBlank { "0 KB" }} · 长按可删除"
                app.api() == null -> "尚未连接服务器。点击右上角「书库」登录 Kavita，然后下载要读的书。"
                else -> "还没有离线内容。点击「书库」浏览并下载，下载后无需联网即可阅读。"
            }
        }
    }

    private fun updateDownloadButton() {
        val n = app.queue.size()
        dlBtn.text = if (n > 0) "下载($n)" else "下载"
    }

    private fun open(s: LocalSeries) {
        SeriesActivity.open(this, s.serverKey, s.seriesId, s.name, s.format, online = false)
    }

    private fun askDelete(s: LocalSeries) {
        confirm("删除离线内容", "删除《${s.name}》已下载的 ${s.chapters.size} 项？\n（不会影响服务器上的文件）", "删除") {
            bg({ app.storage.deleteSeries(s) }) { _, _ -> refresh() }
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

package com.manus.kavitaink.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.R
import com.manus.kavitaink.core.ChapterItem
import com.manus.kavitaink.core.DownloadTask
import com.manus.kavitaink.core.Fmt
import com.manus.kavitaink.core.LocalChapter
import com.manus.kavitaink.core.Storage
import com.manus.kavitaink.core.error
import com.manus.kavitaink.core.formatBytes
import com.manus.kavitaink.download.DownloadQueue
import com.manus.kavitaink.reader.Readers

class SeriesActivity : BaseActivity() {

    companion object {
        fun open(ctx: Context, serverKey: String, seriesId: Int, name: String, format: Int, online: Boolean) {
            ctx.startActivity(
                Intent(ctx, SeriesActivity::class.java)
                    .putExtra("sk", serverKey)
                    .putExtra("sid", seriesId)
                    .putExtra("name", name)
                    .putExtra("format", format)
                    .putExtra("online", online)
            )
        }
    }

    private class Row(
        val id: Int,
        val label: String,
        val pages: Int,
        val bytes: Long,
        val format: Int,
        val online: ChapterItem?,
        val local: LocalChapter?,
    )

    private var sk = ""
    private var seriesId = 0
    private var name = ""
    private var format = Fmt.UNKNOWN
    private var online = false
    private var onlineItems: List<ChapterItem>? = null
    private var locals: Map<Int, LocalChapter> = emptyMap()
    private var onlineError: String? = null

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var adapter: RowAdapter<Row>
    private lateinit var sel: MultiSelect<Row, Int>
    private val queueListener: () -> Unit = { loadLocal() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sk = intent.getStringExtra("sk").orEmpty()
        seriesId = intent.getIntExtra("sid", 0)
        name = intent.getStringExtra("name").orEmpty()
        format = intent.getIntExtra("format", Fmt.UNKNOWN)
        online = intent.getBooleanExtra("online", false)

        val api = app.api()
        val sameServer = api != null && api.serverKey == sk
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += getString(R.string.series_resume) to ::resume
        if (online) actions += getString(R.string.series_download_all) to ::downloadAll
        else if (sameServer) actions += getString(R.string.series_online) to {
            open(this, sk, seriesId, name, format, online = true)
            finish()
        }
        actions += getString(R.string.sel_select) to { if (adapter.items.isNotEmpty()) sel.enter() }

        adapter = RowAdapter(emptyList(), { TwoLineRow(this, false).root }, ::bindRow)
        list = einkList()
        list.adapter = adapter
        sel = MultiSelect(
            this, list, { adapter }, { it.id },
            filters = listOf(
                getString(R.string.sel_not_downloaded) to { r: Row -> r.local == null },
                getString(R.string.sel_downloaded) to { r: Row -> r.local != null },
            ),
        )
        val ops = ArrayList<MultiSelect.Op>()
        if (online) ops += MultiSelect.Op(R.string.sel_download_n, ::downloadSelected)
        ops += MultiSelect.Op(R.string.sel_delete_n, ::deleteSelected)

        val root = root()
        val head = header(name, back = true, actions = actions).first
        sel.build(head, ops)
        root.addView(head)
        root.addView(sel.head)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list.setOnItemClickListener { _, _, pos, _ ->
            if (sel.active) sel.toggle(pos) else adapter.items.getOrNull(pos)?.let(::onTap)
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

        if (online) loadOnline()
    }

    override fun onResume() {
        super.onResume()
        app.queue.addListener(queueListener)
        loadLocal()
    }

    override fun onPause() {
        super.onPause()
        app.queue.removeListener(queueListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sel.active) sel.exit() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun loadLocal() {
        bg({ app.storage.listChapters(sk, seriesId) }) { l, _ ->
            locals = (l ?: emptyList()).associateBy { it.chapterId }
            rebuild()
        }
    }

    private fun loadOnline() {
        val api = app.api() ?: return
        status.text = getString(R.string.series_loading_chapters)
        bg({ api.chapters(seriesId, format) }) { items, err ->
            if (items == null) {
                onlineError = x.error(err)
            } else {
                onlineItems = items
                onlineError = null
            }
            rebuild()
        }
    }

    private fun rebuild() {
        val on = onlineItems
        val rows = ArrayList<Row>()
        if (on != null) {
            val ids = HashSet<Int>()
            for (c in on) {
                ids += c.id
                rows += Row(c.id, c.title(x), c.pages, c.bytes, c.format, c, locals[c.id])
            }
            // 服务器上已删除、但本地仍保留的内容
            locals.values.filter { it.chapterId !in ids }.sortedWith(Storage.chapterOrder).forEach {
                rows += Row(it.chapterId, getString(R.string.series_local_only, it.title(x)), it.meta.pages, it.meta.bytes, it.meta.format, null, it)
            }
        } else {
            locals.values.sortedWith(Storage.chapterOrder).forEach {
                rows += Row(it.chapterId, it.title(x), it.meta.pages, it.meta.bytes, it.meta.format, null, it)
            }
        }
        adapter.update(rows)
        sel.prune()
        updateStatus(rows)
    }

    private fun updateStatus(rows: List<Row>) {
        val downloaded = rows.count { it.local != null }
        val inQueue = rows.count { app.queue.find(sk, it.id) != null }
        val parts = ArrayList<String>()
        parts += resources.getQuantityString(R.plurals.series_status, rows.size, rows.size, downloaded)
        if (inQueue > 0) parts += getString(R.string.series_in_queue, inQueue)
        if (!online) parts += getString(R.string.series_offline_mode)
        else if (onlineItems != null && downloaded < rows.size) parts += getString(R.string.series_tap_to_download)
        val err = onlineError?.let { getString(R.string.series_online_failed, it) + "\n" } ?: ""
        status.text = err + parts.joinToString(" · ")
    }

    private fun bindRow(v: android.view.View, r: Row, @Suppress("UNUSED_PARAMETER") pos: Int) {
        val row = TwoLineRow.of(v)
        val last = prefs.lastChapter(sk, seriesId)
        val playing = r.id == last && r.local != null && !sel.active
        row.title.text = sel.mark(r) + (if (playing) "▶ " else "") + r.label
        val parts = ArrayList<String>()
        if (r.pages > 0 && (r.format != Fmt.EPUB)) parts += resources.getQuantityString(R.plurals.series_pages, r.pages, r.pages)
        formatBytes(r.bytes).takeIf { it.isNotBlank() }?.let { parts += it }
        if (r.format == Fmt.EPUB || r.format == Fmt.PDF) parts += Fmt.label(r.format, x)
        val e = app.queue.find(sk, r.id)
        parts += when {
            r.local != null -> {
                val pct = prefs.percent(sk, r.id)
                if (pct >= 0) getString(R.string.series_downloaded_pct, pct) else getString(R.string.series_downloaded)
            }
            e != null -> DownloadQueue.describe(e, x)
            r.online != null -> getString(R.string.series_not_downloaded)
            else -> ""
        }
        row.sub.text = parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun onTap(r: Row) {
        val local = r.local
        if (local != null) {
            Readers.open(this, local)
            return
        }
        val e = app.queue.find(sk, r.id)
        if (e != null) {
            val actions = ArrayList<Pair<String, () -> Unit>>()
            when (e.state) {
                DownloadQueue.State.RUNNING ->
                    if (e.stopping) actions += getString(R.string.resume) to { app.queue.resume(e) }
                    else actions += getString(R.string.pause) to { app.queue.pause(e) }
                DownloadQueue.State.QUEUED -> actions += getString(R.string.pause) to { app.queue.pause(e) }
                DownloadQueue.State.PAUSED -> actions += getString(R.string.resume) to { app.queue.resume(e) }
                DownloadQueue.State.FAILED -> actions += getString(R.string.dlq_retry_one) to { app.queue.resume(e) }
            }
            actions += getString(R.string.dlq_cancel_one) to {
                confirm(getString(R.string.dlq_cancel_one), getString(R.string.dlq_cancel_one_msg, r.label), getString(R.string.dlq_cancel_one)) {
                    app.queue.cancel(e)
                }
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_dot, r.label, DownloadQueue.describe(e, x)))
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        val c = r.online ?: return
        enqueue(listOf(c))
    }

    private fun enqueue(items: List<ChapterItem>) = withStorageAccess {
        ensureNotificationPermission()
        val tasks = items.map { DownloadTask.of(sk, name, format, it) }
        val n = app.queue.enqueue(tasks)
        status.text = if (n > 0) resources.getQuantityString(R.plurals.series_enqueued, n, n) else getString(R.string.series_already_queued)
        rebuild()
    }

    /** 批量下载：跳过已经下载完或已在队列中的章节，按章节顺序加入下载队列 */
    private fun downloadSelected() {
        val chosen = sel.selectedItems()
        val todo = chosen.filter { it.online != null && it.local == null && app.queue.find(sk, it.id) == null }
        val skipped = chosen.size - todo.size
        sel.exit()
        if (todo.isEmpty()) {
            status.text = getString(R.string.series_already_queued)
            return
        }
        enqueue(todo.mapNotNull { it.online })
        if (skipped > 0) status.text = status.text.toString() + "\n" + resources.getQuantityString(R.plurals.series_skipped, skipped, skipped)
    }

    /** 批量删除：只删已下载的；先算出将释放的空间再确认 */
    private fun deleteSelected() {
        val todo = sel.selectedItems().mapNotNull { it.local }
        if (todo.isEmpty()) {
            status.text = getString(R.string.series_none_downloaded_selected)
            return
        }
        bg({ todo.sumOf { app.storage.sizeOf(it) } }) { bytes, _ ->
            val n = todo.size
            val msg = resources.getQuantityString(R.plurals.series_delete_msg, n, n, formatBytes(bytes ?: 0).ifBlank { "0 KB" })
            confirm(getString(R.string.shelf_delete_title), msg, getString(R.string.delete)) {
                bg({ todo.forEach { app.storage.deleteChapter(it) } }) { _, _ ->
                    todo.forEach { prefs.clearProgress(sk, it.chapterId) }
                    sel.exit()
                    loadLocal()
                }
            }
        }
    }

    private fun downloadAll() {
        // 未下载的，以及已暂停 / 失败的（会接着下载）
        val todo = adapter.items.filter { r ->
            r.online != null && r.local == null && app.queue.find(sk, r.id).let {
                it == null || it.state == DownloadQueue.State.PAUSED || it.state == DownloadQueue.State.FAILED
            }
        }
        if (todo.isEmpty()) {
            status.text = getString(if (onlineItems == null) R.string.series_list_not_loaded else R.string.series_all_done)
            return
        }
        val size = formatBytes(todo.sumOf { it.bytes })
        val n = todo.size
        val msg = if (size.isNotBlank()) resources.getQuantityString(R.plurals.series_download_all_size, n, n, size)
        else resources.getQuantityString(R.plurals.series_download_all_msg, n, n)
        confirm(getString(R.string.series_download_all), msg, getString(R.string.kfonts_download)) {
            enqueue(todo.mapNotNull { it.online })
        }
    }

    private fun resume() {
        val last = prefs.lastChapter(sk, seriesId)
        val sorted = locals.values.sortedWith(Storage.chapterOrder)
        val target = locals[last]
            ?: sorted.firstOrNull { prefs.percent(sk, it.chapterId) in 0..98 }
            ?: sorted.firstOrNull { prefs.percent(sk, it.chapterId) < 0 }
            ?: sorted.firstOrNull()
        if (target == null) {
            status.text = getString(R.string.series_nothing_downloaded)
            return
        }
        Readers.open(this, target)
    }
}

package com.manus.kavitaink.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.core.ChapterItem
import com.manus.kavitaink.core.DownloadTask
import com.manus.kavitaink.core.Fmt
import com.manus.kavitaink.core.KavitaApi
import com.manus.kavitaink.core.Kind
import com.manus.kavitaink.core.LocalChapter
import com.manus.kavitaink.core.Storage
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
        actions += "继续阅读" to ::resume
        if (online) actions += "全部下载" to ::downloadAll
        else if (sameServer) actions += "在线" to {
            open(this, sk, seriesId, name, format, online = true)
            finish()
        }

        adapter = RowAdapter(emptyList(), { TwoLineRow(this, false).root }, ::bindRow)
        val root = root()
        root.addView(header(name, back = true, actions = actions).first)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list = einkList()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> adapter.items.getOrNull(pos)?.let(::onTap) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.let(::onLongTap)
            true
        }
        root.addView(list, lp(MATCH, 0, 1f))
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

    private fun loadLocal() {
        bg({ app.storage.listChapters(sk, seriesId) }) { l, _ ->
            locals = (l ?: emptyList()).associateBy { it.chapterId }
            rebuild()
        }
    }

    private fun loadOnline() {
        val api = app.api() ?: return
        status.text = "正在获取章节列表…"
        bg({ api.chapters(seriesId, format) }) { items, err ->
            if (items == null) {
                onlineError = KavitaApi.friendlyError(err)
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
                rows += Row(c.id, c.label, c.pages, c.bytes, c.format, c, locals[c.id])
            }
            // 服务器上已删除、但本地仍保留的内容
            locals.values.filter { it.chapterId !in ids }.sortedWith(Storage.chapterOrder).forEach {
                rows += Row(it.chapterId, it.label + "（仅本地）", it.meta.pages, it.meta.bytes, it.meta.format, null, it)
            }
        } else {
            locals.values.sortedWith(Storage.chapterOrder).forEach {
                rows += Row(it.chapterId, it.label, it.meta.pages, it.meta.bytes, it.meta.format, null, it)
            }
        }
        adapter.update(rows)
        updateStatus(rows)
    }

    private fun updateStatus(rows: List<Row>) {
        val downloaded = rows.count { it.local != null }
        val inQueue = rows.count { app.queue.find(sk, it.id) != null }
        val sb = StringBuilder()
        onlineError?.let { sb.append("获取在线章节失败：").append(it).append('\n') }
        sb.append("共 ${rows.size} 项 · 已下载 $downloaded")
        if (inQueue > 0) sb.append(" · 队列中 $inQueue")
        if (!online) sb.append(" · 离线模式")
        else if (onlineItems != null && downloaded < rows.size) sb.append(" · 点击未下载项即可下载")
        status.text = sb
    }

    private fun bindRow(v: android.view.View, r: Row, @Suppress("UNUSED_PARAMETER") pos: Int) {
        val row = TwoLineRow.of(v)
        val last = prefs.lastChapter(sk, seriesId)
        row.title.text = if (r.id == last && r.local != null) "▶ ${r.label}" else r.label
        val parts = ArrayList<String>()
        if (r.pages > 0 && (r.format != Fmt.EPUB)) parts += "${r.pages} 页"
        formatBytes(r.bytes).takeIf { it.isNotBlank() }?.let { parts += it }
        if (r.format == Fmt.EPUB || r.format == Fmt.PDF) parts += Fmt.label(r.format)
        val e = app.queue.find(sk, r.id)
        parts += when {
            r.local != null -> {
                val pct = prefs.percent(sk, r.id)
                if (pct >= 0) "已下载 · 读到 $pct%" else "已下载"
            }
            e != null -> DownloadQueue.describe(e)
            r.online != null -> "未下载"
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
                    if (e.stopping) actions += "继续" to { app.queue.resume(e) }
                    else actions += "暂停" to { app.queue.pause(e) }
                DownloadQueue.State.QUEUED -> actions += "暂停" to { app.queue.pause(e) }
                DownloadQueue.State.PAUSED -> actions += "继续" to { app.queue.resume(e) }
                DownloadQueue.State.FAILED -> actions += "重试（接着下载）" to { app.queue.resume(e) }
            }
            actions += "取消下载" to {
                confirm("取消下载", "${r.label}\n\n将删除已下载的未完成部分。", "取消下载") { app.queue.cancel(e) }
            }
            AlertDialog.Builder(this)
                .setTitle(r.label + " · " + DownloadQueue.describe(e))
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        val c = r.online ?: return
        enqueue(listOf(c))
    }

    private fun onLongTap(r: Row) {
        val local = r.local ?: return
        val opts = arrayOf("删除离线内容", "从头阅读")
        AlertDialog.Builder(this)
            .setTitle(r.label)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> confirm("删除", "删除「${r.label}」的离线文件？", "删除") {
                        bg({ app.storage.deleteChapter(local) }) { _, _ ->
                            prefs.clearProgress(sk, r.id)
                            loadLocal()
                        }
                    }
                    1 -> {
                        prefs.clearProgress(sk, r.id)
                        Readers.open(this, local)
                    }
                }
            }
            .show()
    }

    private fun enqueue(items: List<ChapterItem>) {
        ensureNotificationPermission()
        val tasks = items.map { DownloadTask.of(sk, name, format, it) }
        val n = app.queue.enqueue(tasks)
        status.text = if (n > 0) "已加入下载队列：$n 项（可离开此页面，下载在后台继续）" else "已在队列中或已下载"
        rebuild()
    }

    private fun downloadAll() {
        // 未下载的，以及已暂停 / 失败的（会接着下载）
        val todo = adapter.items.filter { r ->
            r.online != null && r.local == null && app.queue.find(sk, r.id).let {
                it == null || it.state == DownloadQueue.State.PAUSED || it.state == DownloadQueue.State.FAILED
            }
        }
        if (todo.isEmpty()) {
            status.text = if (onlineItems == null) "章节列表尚未加载" else "全部内容已下载或已在队列中"
            return
        }
        val size = formatBytes(todo.sumOf { it.bytes })
        confirm("全部下载", "将下载 ${todo.size} 项" + (if (size.isNotBlank()) "，约 $size" else "") + "。", "下载") {
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
            status.text = "还没有已下载的内容，请先点击章节下载"
            return
        }
        Readers.open(this, target)
    }

    @Suppress("unused")
    private fun kindLabel(k: Kind) = when (k) {
        Kind.EPUB -> "EPUB"
        Kind.PDF -> "PDF"
        Kind.IMAGES -> "图片"
    }
}

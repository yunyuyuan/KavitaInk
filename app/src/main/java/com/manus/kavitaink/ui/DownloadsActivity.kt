package com.manus.kavitaink.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.download.DownloadQueue
import com.manus.kavitaink.download.DownloadQueue.State

class DownloadsActivity : BaseActivity() {

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var toggleBtn: Button
    private val adapter = RowAdapter<DownloadQueue.Entry>(
        emptyList(),
        { TwoLineRow(this, false).root },
        { v, e, _ ->
            val r = TwoLineRow.of(v)
            r.title.text = "${e.task.seriesName} · ${e.task.label}"
            r.sub.text = DownloadQueue.describe(e)
        },
    )
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        val (bar, btns) = header(
            "下载队列", back = true, actions = listOf(
                "全部暂停" to { toggleAll() },
                "更多" to { more() },
            )
        )
        toggleBtn = btns[0]
        root.addView(bar)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list = einkList()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.let(::itemActions)
        }
        root.addView(list, lp(MATCH, 0, 1f))
        root.addView(pagerBar(list))
        setContentView(root)
        pagedList = list
    }

    override fun onResume() {
        super.onResume()
        app.queue.addListener(listener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        app.queue.removeListener(listener)
    }

    /** 有正在进行的任务 → 全部暂停；否则 → 全部继续 */
    private fun toggleAll() {
        if (hasActive()) app.queue.pauseAll() else app.queue.resumeAll()
    }

    private fun hasActive() = app.queue.snapshot().any {
        (it.state == State.QUEUED || it.state == State.RUNNING) && !it.stopping
    }

    private fun more() {
        val opts = arrayOf("重试全部失败项", "清除失败项", "全部取消（删除未完成的文件）")
        AlertDialog.Builder(this)
            .setTitle("下载队列")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> app.queue.retryAll()
                    1 -> app.queue.clearFailed()
                    2 -> if (app.queue.size() > 0) {
                        confirm("全部取消", "取消队列中的所有下载，并删除已下载的未完成部分？", "全部取消") { app.queue.cancelAll() }
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun itemActions(e: DownloadQueue.Entry) {
        val title = "${e.task.seriesName} · ${e.task.label}"
        val actions = ArrayList<Pair<String, () -> Unit>>()
        when (e.state) {
            State.RUNNING -> if (e.stopping) actions += "继续" to { app.queue.resume(e) }
            else actions += "暂停" to { app.queue.pause(e) }
            State.QUEUED -> actions += "暂停" to { app.queue.pause(e) }
            State.PAUSED -> actions += "继续" to { app.queue.resume(e) }
            State.FAILED -> actions += "重试（接着下载）" to { app.queue.resume(e) }
        }
        actions += "取消下载" to {
            confirm("取消下载", "$title\n\n将删除已下载的未完成部分。", "取消下载") { app.queue.cancel(e) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun refresh() {
        val entries = app.queue.snapshot()
        adapter.update(entries)
        val running = entries.count { it.state == State.RUNNING }
        val queued = entries.count { it.state == State.QUEUED }
        val paused = entries.count { it.state == State.PAUSED }
        val failed = entries.count { it.state == State.FAILED }
        status.text = if (entries.isEmpty()) {
            "没有进行中的下载。下载完成的内容在「书架」中。"
        } else {
            val parts = ArrayList<String>()
            if (running > 0) parts += "下载中 $running"
            if (queued > 0) parts += "排队 $queued"
            if (paused > 0) parts += "已暂停 $paused"
            if (failed > 0) parts += "失败 $failed"
            "共 ${entries.size} 项 · " + parts.joinToString(" · ") + "\n点击某一项可暂停 / 继续 / 取消；暂停后已下载的部分会保留"
        }
        val active = hasActive()
        toggleBtn.text = if (active) "全部暂停" else "全部继续"
        toggleBtn.visibility = if (active || paused > 0 || failed > 0) View.VISIBLE else View.GONE
    }
}

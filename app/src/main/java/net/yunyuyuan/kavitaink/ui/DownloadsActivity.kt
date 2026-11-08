package net.yunyuyuan.kavitaink.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import net.yunyuyuan.kavitaink.R
import net.yunyuyuan.kavitaink.download.DownloadQueue
import net.yunyuyuan.kavitaink.download.DownloadQueue.State

class DownloadsActivity : BaseActivity() {

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var toggleBtn: Button
    private val adapter = RowAdapter<DownloadQueue.Entry>(
        emptyList(),
        { TwoLineRow(this, false).root },
        { v, e, _ ->
            val r = TwoLineRow.of(v)
            r.title.text = getString(R.string.title_dot, e.task.seriesName, e.task.title(x))
            r.sub.text = DownloadQueue.describe(e, x)
        },
    )
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        val (bar, btns) = header(
            getString(R.string.dlq_title), back = true, actions = listOf(
                getString(R.string.action_pause_all) to { toggleAll() },
                getString(R.string.more) to { more() },
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
        if (hasActive()) app.queue.pauseAll() else withStorageAccess { app.queue.resumeAll() }
    }

    private fun hasActive() = app.queue.snapshot().any {
        (it.state == State.QUEUED || it.state == State.RUNNING) && !it.stopping
    }

    private fun more() {
        val opts = arrayOf(getString(R.string.dlq_retry_failed), getString(R.string.dlq_clear_failed), getString(R.string.dlq_cancel_all))
        AlertDialog.Builder(this)
            .setTitle(R.string.dlq_title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> withStorageAccess { app.queue.retryAll() }
                    1 -> app.queue.clearFailed()
                    2 -> if (app.queue.size() > 0) {
                        confirm(getString(R.string.dlq_cancel_all_title), getString(R.string.dlq_cancel_all_msg), getString(R.string.dlq_cancel_all_title)) { app.queue.cancelAll() }
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun itemActions(e: DownloadQueue.Entry) {
        val title = getString(R.string.title_dot, e.task.seriesName, e.task.title(x))
        val actions = ArrayList<Pair<String, () -> Unit>>()
        when (e.state) {
            State.RUNNING -> if (e.stopping) actions += getString(R.string.resume) to { withStorageAccess { app.queue.resume(e) } }
            else actions += getString(R.string.pause) to { app.queue.pause(e) }
            State.QUEUED -> actions += getString(R.string.pause) to { app.queue.pause(e) }
            State.PAUSED -> actions += getString(R.string.resume) to { withStorageAccess { app.queue.resume(e) } }
            State.FAILED -> actions += getString(R.string.dlq_retry_one) to { withStorageAccess { app.queue.resume(e) } }
        }
        actions += getString(R.string.dlq_cancel_one) to {
            confirm(getString(R.string.dlq_cancel_one), getString(R.string.dlq_cancel_one_msg, title), getString(R.string.dlq_cancel_one)) { app.queue.cancel(e) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.close, null)
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
            getString(R.string.dlq_empty)
        } else {
            val parts = ArrayList<String>()
            if (running > 0) parts += getString(R.string.dlq_n_running, running)
            if (queued > 0) parts += getString(R.string.dlq_n_queued, queued)
            if (paused > 0) parts += getString(R.string.dlq_n_paused, paused)
            if (failed > 0) parts += getString(R.string.dlq_n_failed, failed)
            (listOf(resources.getQuantityString(R.plurals.dlq_total, entries.size, entries.size)) + parts).joinToString(" · ") +
                "\n" + getString(R.string.dlq_hint)
        }
        val active = hasActive()
        toggleBtn.text = getString(if (active) R.string.action_pause_all else R.string.action_resume_all)
        toggleBtn.visibility = if (active || paused > 0 || failed > 0) View.VISIBLE else View.GONE
    }
}

package com.manus.kavitaink.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.manus.kavitaink.App
import com.manus.kavitaink.core.DownloadTask
import com.manus.kavitaink.core.Downloader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 下载队列：持久化到 SharedPreferences，应用被杀后重新打开可继续。
 *
 * 状态：排队 → 下载中 → 完成（移出队列）
 *                  ↘ 已暂停（保留已下载部分，不会自动继续） → 继续 → 排队
 *                  ↘ 失败（保留已下载部分）               → 重试 → 排队
 * 取消 = 移出队列并删除未完成的文件。
 *
 * 进度回调节流为最多每 2 秒一次，避免墨水屏频繁局部刷新。
 */
class DownloadQueue(private val app: App) {

    enum class State { QUEUED, RUNNING, PAUSED, FAILED }

    /** 运行中的任务被要求停止的原因 */
    enum class Stop { NONE, PAUSE, CANCEL }

    class Entry(val task: DownloadTask) {
        @Volatile var state = State.QUEUED
        @Volatile var error = ""
        /** 临时提示，例如“网络中断，10 秒后自动重试” */
        @Volatile var note = ""
        @Volatile var done = 0L
        @Volatile var total = 0L
        /** done/total 的单位：true = 页，false = 字节 */
        @Volatile var pages = false
        @Volatile var stop = Stop.NONE
        /** 由下载服务设置：断开当前连接 */
        @Volatile var abort: (() -> Unit)? = null

        val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val stopping: Boolean get() = state == State.RUNNING && stop != Stop.NONE
        fun matches(serverKey: String, chapterId: Int) = task.serverKey == serverKey && task.chapterId == chapterId
    }

    private val sp = app.getSharedPreferences("queue", Context.MODE_PRIVATE)
    private val entries = ArrayList<Entry>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var lastNotify = 0L
    private var notifyPending = false
    private val notifyRunnable = Runnable {
        notifyPending = false
        lastNotify = SystemClock.uptimeMillis()
        listeners.forEach { it() }
    }

    init {
        load()
    }

    // ---------- 查询 ----------

    @Synchronized
    fun snapshot(): List<Entry> = ArrayList(entries)

    @Synchronized
    fun find(serverKey: String, chapterId: Int): Entry? = entries.firstOrNull { it.matches(serverKey, chapterId) }

    /** 需要下载服务处理的数量（排队 + 下载中） */
    @Synchronized
    fun pendingCount(): Int = entries.count { it.state == State.QUEUED || it.state == State.RUNNING }

    @Synchronized
    fun count(state: State): Int = entries.count { it.state == state }

    @Synchronized
    fun size(): Int = entries.size

    // ---------- 用户操作 ----------

    /** 加入队列；已暂停 / 失败的同一章节会被重新排队。返回实际新加入或恢复的数量 */
    fun enqueue(tasks: List<DownloadTask>): Int {
        var added = 0
        synchronized(this) {
            for (t in tasks) {
                if (app.storage.isComplete(t.serverKey, t.seriesId, t.chapterId)) continue
                val existing = entries.firstOrNull { it.matches(t.serverKey, t.chapterId) }
                if (existing != null) {
                    if (requeueLocked(existing)) added++
                    continue
                }
                entries += Entry(t).also { estimate(it) }
                added++
            }
        }
        changed(startService = added > 0)
        return added
    }

    /** 暂停：排队中的直接暂停；下载中的会立即断开连接，已下载部分保留 */
    fun pause(e: Entry) {
        var abort: (() -> Unit)? = null
        synchronized(this) {
            when (e.state) {
                State.QUEUED -> e.state = State.PAUSED
                State.RUNNING -> if (e.stop == Stop.NONE) {
                    e.stop = Stop.PAUSE
                    abort = e.abort
                }
                else -> {}
            }
        }
        abort?.let(::abortAsync)
        changed()
    }

    fun pauseAll() {
        val aborts = ArrayList<() -> Unit>()
        synchronized(this) {
            for (e in entries) {
                when (e.state) {
                    State.QUEUED -> e.state = State.PAUSED
                    State.RUNNING -> if (e.stop == Stop.NONE) {
                        e.stop = Stop.PAUSE
                        e.abort?.let(aborts::add)
                    }
                    else -> {}
                }
            }
        }
        aborts.forEach(::abortAsync)
        changed()
    }

    /** 继续（也用于失败重试）：从断点接着下载 */
    fun resume(e: Entry) {
        val ok = synchronized(this) { requeueLocked(e) }
        changed(startService = ok)
    }

    /** 继续全部已暂停的项；失败项一并重试 */
    fun resumeAll() {
        var any = false
        synchronized(this) { entries.forEach { if (requeueLocked(it)) any = true } }
        changed(startService = any)
    }

    fun retryAll() {
        var any = false
        synchronized(this) { entries.filter { it.state == State.FAILED }.forEach { if (requeueLocked(it)) any = true } }
        changed(startService = any)
    }

    /** 取消：移出队列并删除未完成的文件 */
    fun cancel(e: Entry) {
        var abort: (() -> Unit)? = null
        var removed = false
        synchronized(this) {
            if (e.state == State.RUNNING) {
                e.stop = Stop.CANCEL
                abort = e.abort
            } else {
                removed = entries.remove(e)
            }
        }
        abort?.let(::abortAsync)
        if (removed) deletePartialAsync(e)
        changed()
    }

    fun cancelAll() {
        snapshot().forEach { cancel(it) }
    }

    fun clearFailed() {
        val gone: List<Entry>
        synchronized(this) {
            gone = entries.filter { it.state == State.FAILED }
            entries.removeAll(gone.toSet())
        }
        gone.forEach(::deletePartialAsync)
        changed()
    }

    // ---------- 供 DownloadService 调用 ----------

    /** 取出下一个排队的任务并标记为下载中（原子操作，避免与用户的“暂停”竞争） */
    fun takeNext(): Entry? {
        val e = synchronized(this) {
            entries.firstOrNull { it.state == State.QUEUED }?.also {
                it.state = State.RUNNING
                it.stop = Stop.NONE
                it.error = ""
                it.note = ""
            }
        }
        if (e != null) notifyNow()
        return e
    }

    fun markDone(e: Entry) {
        synchronized(this) { entries.remove(e) }
        e.abort = null
        persist()
        notifyNow()
    }

    /** 下载线程因暂停 / 取消而退出后调用 */
    fun finishStopped(e: Entry) {
        var delete = false
        synchronized(this) {
            e.abort = null
            e.note = ""
            when (e.stop) {
                Stop.CANCEL -> {
                    entries.remove(e)
                    delete = true
                }
                Stop.PAUSE -> e.state = State.PAUSED
                // 停止请求发出后用户又点了“继续”：重新排队
                Stop.NONE -> e.state = State.QUEUED
            }
            e.stop = Stop.NONE
        }
        // 在下载线程中调用，可以直接删除
        if (delete) app.storage.deletePartial(e.task.serverKey, e.task.seriesId, e.task.chapterId)
        persist()
        notifyNow()
    }

    fun markFailed(e: Entry, msg: String) {
        synchronized(this) {
            e.abort = null
            e.state = State.FAILED
            e.error = msg
            e.note = ""
            e.stop = Stop.NONE
        }
        persist()
        notifyNow()
    }

    fun progress(e: Entry, done: Long, total: Long, pages: Boolean) {
        e.done = done
        e.total = total
        e.pages = pages
        notifyThrottled()
    }

    fun note(e: Entry, text: String) {
        e.note = text
        notifyNow()
    }

    // ---------- 监听 ----------

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    // ---------- 内部 ----------

    private fun requeueLocked(e: Entry): Boolean = when (e.state) {
        State.PAUSED, State.FAILED -> {
            e.state = State.QUEUED
            e.error = ""
            e.stop = Stop.NONE
            true
        }
        // 正在暂停 / 取消的过程中又点了继续：撤销停止请求（连接若已断开，会自动重新排队）
        State.RUNNING -> if (e.stop != Stop.NONE) {
            e.stop = Stop.NONE
            true
        } else false
        State.QUEUED -> false
    }

    private fun changed(startService: Boolean = false) {
        persist()
        notifyNow()
        if (startService) DownloadService.start(app)
    }

    private fun abortAsync(fn: () -> Unit) {
        // disconnect() 可能触发网络 I/O，不能在主线程执行
        Thread({ runCatching(fn) }, "kavita-abort").start()
    }

    private fun deletePartialAsync(e: Entry) {
        Thread({
            runCatching { app.storage.deletePartial(e.task.serverKey, e.task.seriesId, e.task.chapterId) }
        }, "kavita-cleanup").start()
    }

    /** 从磁盘上的未完成文件估算进度 */
    private fun estimate(e: Entry) {
        runCatching { Downloader.partialProgress(app.storage, e.task) }.getOrNull()?.let { (d, t, p) ->
            e.done = d
            e.total = t
            e.pages = p
        }
    }

    private fun notifyNow() {
        main.post {
            main.removeCallbacks(notifyRunnable)
            notifyPending = true
            main.post(notifyRunnable)
        }
    }

    private fun notifyThrottled() {
        main.post {
            if (notifyPending) return@post
            val wait = THROTTLE_MS - (SystemClock.uptimeMillis() - lastNotify)
            notifyPending = true
            if (wait <= 0) main.post(notifyRunnable) else main.postDelayed(notifyRunnable, wait)
        }
    }

    // ---------- 持久化 ----------

    private fun persist() {
        val arr = JSONArray()
        for (e in snapshot()) {
            // 下载中的任务按“排队”保存：应用被杀后下次打开自动继续；
            // 正在暂停的按“已暂停”保存
            val st = when {
                e.state == State.RUNNING && e.stop == Stop.PAUSE -> State.PAUSED
                e.state == State.RUNNING -> State.QUEUED
                else -> e.state
            }
            if (e.state == State.RUNNING && e.stop == Stop.CANCEL) continue
            arr.put(
                JSONObject()
                    .put("task", e.task.toJson())
                    .put("state", st.name)
                    .put("error", e.error)
                    .put("done", e.done)
                    .put("total", e.total)
                    .put("pages", e.pages)
            )
        }
        sp.edit().putString("entries", arr.toString()).apply()
    }

    private fun load() {
        val text = sp.getString("entries", null) ?: return
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(DownloadTask.fromJson(o.getJSONObject("task")))
                e.state = when {
                    o.has("state") -> runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.QUEUED)
                    o.optBoolean("failed") -> State.FAILED // 旧版本格式
                    else -> State.QUEUED
                }
                if (e.state == State.RUNNING) e.state = State.QUEUED
                e.error = o.optString("error", "")
                e.done = o.optLong("done", 0)
                e.total = o.optLong("total", 0)
                e.pages = o.optBoolean("pages", false)
                estimate(e)
                entries += e
            }
        }
    }

    companion object {
        const val THROTTLE_MS = 2000L

        fun formatProgress(e: Entry): String {
            if (e.total <= 0) return if (e.done > 0) com.manus.kavitaink.core.formatBytes(e.done) else ""
            return if (e.pages) "${e.done} / ${e.total} 页"
            else "${com.manus.kavitaink.core.formatBytes(e.done)} / ${com.manus.kavitaink.core.formatBytes(e.total)}"
        }

        /** 列表里显示的状态文字 */
        fun describe(e: Entry): String {
            val prog = formatProgress(e)
            val pct = if (e.total > 0) "${e.percent}%" else ""
            val detail = listOf(pct, prog).filter { it.isNotBlank() }.joinToString(" · ")
            return when (e.state) {
                State.RUNNING -> when (e.stop) {
                    Stop.PAUSE -> "正在暂停…"
                    Stop.CANCEL -> "正在取消…"
                    Stop.NONE -> buildString {
                        append("下载中")
                        if (detail.isNotBlank()) append(' ').append(detail)
                        if (e.note.isNotBlank()) append(" · ").append(e.note)
                    }
                }
                State.QUEUED -> if (e.done > 0 && e.total > 0) "排队中 · 已下载 ${e.percent}%" else "排队中"
                State.PAUSED -> if (detail.isNotBlank()) "已暂停 · $detail" else "已暂停"
                State.FAILED -> "失败：${e.error}" + (if (e.done > 0 && e.total > 0) "（已下载 ${e.percent}%，重试会接着下载）" else "")
            }
        }
    }
}

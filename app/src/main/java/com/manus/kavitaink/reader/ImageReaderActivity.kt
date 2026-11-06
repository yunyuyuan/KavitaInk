package com.manus.kavitaink.reader

import android.app.AlertDialog
import android.graphics.Bitmap
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.manus.kavitaink.core.Kind
import com.manus.kavitaink.ui.MATCH
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 漫画（逐页图片）与 PDF 阅读器 */
class ImageReaderActivity : ReaderActivity() {

    private lateinit var view: PageView
    private var source: PageSource? = null
    private var page = 0
    private val cache = HashMap<Int, Bitmap>()
    private val inflight = HashSet<Int>()
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor()
    private var gen = 0
    private var started = false
    /** 正在等待解码完成后显示的页（-1 表示没有） */
    private var waiting = -1
    private var waitingFromEnd = false

    override val rtl: Boolean get() = prefs.mangaRtl

    private val count: Int get() = source?.count ?: 0

    override fun setup() {
        view = PageView(this)
        view.fitWidth = prefs.fitMode == 1
        view.contrast = prefs.contrast
        view.setOnTouchListener(touch)
        frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        source = try {
            if (chapter.kind == Kind.PDF) PdfSource(chapter.pdfFile) else ImageFolderSource(chapter.pagesDir)
        } catch (t: Throwable) {
            null
        }
        val n = count
        if (n == 0) {
            frame.post { showHint("无法打开该内容（文件缺失或格式不受支持）") }
            return
        }
        page = (prefs.position(chapter).toIntOrNull() ?: 0).coerceIn(0, n - 1)
        view.onSize = { _, _ ->
            gen++
            cache.clear()
            inflight.clear()
            waiting = -1
            started = true
            show(page, false)
        }
    }

    private fun show(i: Int, fromEnd: Boolean) {
        page = i
        updateInfo()
        val b = cache[i]
        if (b != null) {
            waiting = -1
            view.setPage(b, fromEnd)
            afterShow()
        } else {
            waiting = i
            waitingFromEnd = fromEnd
            if (i !in inflight) load(i)
        }
    }

    private fun afterShow() {
        // 只保留当前页与下一页，控制内存
        cache.keys.filter { it != page && it != page + 1 }.forEach { cache.remove(it) }
        if (page + 1 < count && !cache.containsKey(page + 1) && (page + 1) !in inflight) load(page + 1)
    }

    private fun load(i: Int) {
        val src = source ?: return
        inflight += i
        val g = gen
        val w = view.width
        val h = view.height
        val fitW = view.fitWidth
        decoder.execute {
            val b = try {
                src.decode(i, w, h, fitW)
            } catch (oom: OutOfMemoryError) {
                null
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (g != gen || isFinishing || isDestroyed) return@runOnUiThread
                inflight -= i
                if (b != null) cache[i] = b
                if (waiting == i) {
                    waiting = -1
                    if (b != null) {
                        view.setPage(b, waitingFromEnd)
                        afterShow()
                    } else {
                        showHint("第 ${i + 1} 页无法显示（图片格式可能不受本机支持）")
                    }
                }
            }
        }
    }

    override fun nextPage() {
        if (!started || count == 0) return
        if (view.stepForward()) { turned(); return }
        if (page + 1 < count) {
            show(page + 1, false)
            turned()
        } else {
            reachedEnd()
        }
    }

    override fun prevPage() {
        if (!started || count == 0) return
        if (view.stepBack()) { turned(); return }
        if (page > 0) {
            show(page - 1, true)
            turned()
        } else {
            reachedStart()
        }
    }

    override fun progressText(): String = if (count > 0) "${page + 1} / $count" else ""

    override fun savePosition() {
        val n = count
        if (n == 0) return
        prefs.savePosition(chapter, page.toString(), ((page + 1) * 100) / n)
    }

    override fun buildMenu(rows: LinearLayout) {
        menuRow(
            "跳页" to ::askJump,
            "方向：" + (if (prefs.mangaRtl) "右→左" else "左→右") to {
                prefs.mangaRtl = !prefs.mangaRtl
                rebuildMenu()
            },
        )
        menuRow(
            "缩放：" + (if (view.fitWidth) "适应宽度" else "适应屏幕") to {
                prefs.fitMode = if (view.fitWidth) 0 else 1
                view.fitWidth = prefs.fitMode == 1
                gen++
                cache.clear()
                inflight.clear()
                show(page, false)
                rebuildMenu()
            },
            "对比度：" + (if (view.contrast) "增强" else "原图") to {
                prefs.contrast = !prefs.contrast
                view.contrast = prefs.contrast
                rebuildMenu()
            },
        )
    }

    private fun askJump() {
        val n = count
        if (n == 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - $n"
            setText((page + 1).toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("跳到第几页")
            .setView(input)
            .setPositiveButton("跳转") { _, _ ->
                val p = input.text.toString().toIntOrNull()
                if (p != null) {
                    hideMenu()
                    show((p - 1).coerceIn(0, n - 1), false)
                    turned()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        gen++
        decoder.shutdownNow()
        source?.let { s -> Thread { runCatching { s.close() } }.start() }
        cache.clear()
    }
}

package com.manus.kavitaink.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.manus.kavitaink.core.EpubBook
import com.manus.kavitaink.core.Paginator
import com.manus.kavitaink.ui.MATCH
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.floor
import kotlin.math.max

/**
 * EPUB 阅读器：资源直接从 zip 中读出交给 WebView（不解压到磁盘），
 * 通过 CSS 多栏把每个章节切成整屏页面，翻页 = 视口水平跳一屏，无滚动动画。
 */
class EpubReaderActivity : ReaderActivity() {

    private sealed class Target {
        object First : Target()
        object Last : Target()
        class Fraction(val f: Float) : Target()
        class Anchor(val id: String) : Target()
    }

    companion object {
        private const val HOST = "epub.local"
        private const val BASE = "https://$HOST/"
        private val FONT_STEPS = listOf(80, 90, 100, 110, 120, 135, 150, 175, 200)
        private val LINE_STEPS = listOf(0, 130, 150, 160, 180, 200)
        private val MARGIN_STEPS = listOf(8, 12, 16, 24, 32)
    }

    @Volatile private var book: EpubBook? = null
    private var web: WebView? = null
    private var spine = 0
    private var page = 0
    private var pageCount = 1
    private var gen = 0
    private var target: Target = Target.Fraction(0f)
    private var ready = false
    private var currentUrl = ""
    @Volatile private var style = Paginator.Style()
    @Volatile private var cssW = 0
    @Volatile private var cssH = 0
    private var lastW = 0
    private var lastH = 0

    override val infoGravity: Int get() = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

    private val spineCount get() = book?.spine?.size ?: 0

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun setup() {
        style = prefs.paginatorStyle()
        // 仅在可调试构建中允许 chrome://inspect 远程调试（正式包不受影响）
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
        val w = try {
            WebView(this)
        } catch (t: Throwable) {
            frame.post { showHint("本机缺少 WebView 组件，无法显示 EPUB") }
            return
        }
        web = w
        w.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // 必须为 true，注入的 viewport meta（锁定缩放为 1）才会生效
            useWideViewPort = true
            loadWithOverviewMode = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = prefs.fontZoom
            defaultTextEncodingName = "utf-8"
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
        }
        w.setBackgroundColor(0xFFFFFFFF.toInt())
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.isLongClickable = false
        w.isHapticFeedbackEnabled = false
        w.setOnLongClickListener { true }
        w.setOnTouchListener(touch)
        w.addJavascriptInterface(Bridge(), "EinkBridge")
        w.webViewClient = Client()
        w.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val nw = r - l
            val nh = b - t
            if (nw > 0 && nh > 0 && (nw != lastW || nh != lastH)) {
                val first = lastW == 0
                lastW = nw
                lastH = nh
                if (!first && ready) relayout()
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))

        bg({ EpubBook(chapter.epubFile) }) { b, err ->
            if (b == null) {
                showHint("无法打开 EPUB：" + (err?.message ?: "文件损坏"))
                return@bg
            }
            book = b
            val parts = prefs.position(chapter).split(':')
            val s = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val p = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val c = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val t = if (p <= 0) Target.First else Target.Fraction(p.toFloat() / max(1, c))
            load(s.coerceIn(0, max(0, b.spine.size - 1)), t)
        }
    }

    private fun load(index: Int, t: Target) {
        val b = book ?: return
        val w = web ?: return
        if (b.spine.isEmpty()) {
            showHint("这本 EPUB 没有可阅读的内容")
            return
        }
        if (w.width == 0 || w.height == 0) {
            w.post { load(index, t) }
            return
        }
        spine = index.coerceIn(0, b.spine.size - 1)
        target = t
        gen++
        ready = false
        val d = resources.displayMetrics.density
        cssW = floor(w.width / d).toInt()
        cssH = floor(w.height / d).toInt()
        if (t !is Target.First) w.visibility = View.INVISIBLE
        currentUrl = BASE + b.spine[spine].split('/').joinToString("/") { Uri.encode(it) }
        w.loadUrl(currentUrl)
    }

    private fun relayout() {
        style = prefs.paginatorStyle()
        web?.settings?.textZoom = prefs.fontZoom
        val f = if (pageCount > 0) page.toFloat() / pageCount else 0f
        load(spine, if (page == 0) Target.First else Target.Fraction(f))
    }

    private fun laidOut(count: Int) {
        pageCount = max(1, count)
        when (val t = target) {
            is Target.First -> go(0)
            is Target.Last -> go(pageCount - 1)
            is Target.Fraction -> go(floor(t.f * pageCount + 0.001f).toInt())
            is Target.Anchor -> web?.evaluateJavascript("__einkFind(${JSONObject.quote(t.id)})") { r ->
                val p = r?.trim('"')?.toIntOrNull() ?: 0
                go(if (p < 0) 0 else p)
            }
        }
    }

    private fun go(p: Int) {
        val w = web ?: return
        page = p.coerceIn(0, pageCount - 1)
        w.evaluateJavascript("__einkGo($page)") {
            if (!ready) {
                ready = true
                w.visibility = View.VISIBLE
            }
        }
        updateInfo()
        savePosition()
    }

    override fun nextPage() {
        if (!ready) return
        when {
            page < pageCount - 1 -> { go(page + 1); turned() }
            spine < spineCount - 1 -> { load(spine + 1, Target.First); turned() }
            else -> reachedEnd()
        }
    }

    override fun prevPage() {
        if (!ready) return
        when {
            page > 0 -> { go(page - 1); turned() }
            spine > 0 -> { load(spine - 1, Target.Last); turned() }
            else -> reachedStart()
        }
    }

    private fun bookPercent(): Int {
        val n = spineCount
        if (n == 0) return 0
        return (((spine + (page + 1f) / max(1, pageCount)) / n) * 100f).toInt().coerceIn(0, 100)
    }

    override fun progressText(): String =
        if (spineCount == 0) "" else "${page + 1}/$pageCount · 第 ${spine + 1}/$spineCount 节 · ${bookPercent()}%"

    override fun savePosition() {
        if (book == null) return
        prefs.savePosition(chapter, "$spine:$page:$pageCount", bookPercent())
    }

    override fun buildMenu(rows: LinearLayout) {
        menuRow(
            "目录" to ::showToc,
            "字号 −" to { step(FONT_STEPS, prefs.fontZoom, -1) { prefs.fontZoom = it } },
            "字号 +" to { step(FONT_STEPS, prefs.fontZoom, +1) { prefs.fontZoom = it } },
        )
        menuRow(
            "行距 " + (if (prefs.lineHeight == 0) "默认" else String.format(java.util.Locale.US, "%.1f", prefs.lineHeight / 100.0)) to {
                cycle(LINE_STEPS, prefs.lineHeight) { prefs.lineHeight = it }
            },
            "边距 ${prefs.margin}" to { cycle(MARGIN_STEPS, prefs.margin) { prefs.margin = it } },
            "字体 " + arrayOf("默认", "衬线", "无衬线")[prefs.fontFamily.coerceIn(0, 2)] to {
                prefs.fontFamily = (prefs.fontFamily + 1) % 3
                relayout()
                rebuildMenu()
            },
        )
    }

    private fun step(list: List<Int>, cur: Int, dir: Int, set: (Int) -> Unit) {
        val i = list.indexOf(cur).let { if (it < 0) list.indexOf(100).coerceAtLeast(0) else it }
        val n = (i + dir).coerceIn(0, list.size - 1)
        if (n == i) return
        set(list[n])
        relayout()
        rebuildMenu()
    }

    private fun cycle(list: List<Int>, cur: Int, set: (Int) -> Unit) {
        val i = list.indexOf(cur)
        set(list[(i + 1).mod(list.size)])
        relayout()
        rebuildMenu()
    }

    private fun showToc() {
        val b = book ?: return
        val toc = b.toc
        if (toc.isEmpty()) {
            showHint("这本书没有目录")
            return
        }
        val labels = toc.map { "\u3000".repeat(it.level.coerceAtMost(4)) + it.title }.toTypedArray()
        var checked = 0
        toc.forEachIndexed { i, e -> if (b.spineIndexOf(e.path) in 0..spine) checked = i }
        android.app.AlertDialog.Builder(this)
            .setTitle(b.title.ifBlank { "目录" })
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                val e = toc[which]
                val idx = b.spineIndexOf(e.path)
                if (idx < 0) {
                    showHint("目录项指向的内容不存在")
                    return@setSingleChoiceItems
                }
                hideMenu()
                load(idx, e.fragment?.let { Target.Anchor(it) } ?: Target.First)
                turned()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- WebView ----------

    private inner class Bridge {
        @JavascriptInterface
        fun onLayout(g: Int, count: Int) {
            runOnUiThread { if (g == gen && !isFinishing) laidOut(count) }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun onPageFinished(view: WebView, url: String) {
            if (url == currentUrl) view.evaluateJavascript(Paginator.script(gen, style, cssW, cssH), null)
        }
    }

    private fun serve(u: Uri): WebResourceResponse {
        val b = book
        if (b == null || u.host != HOST) return notFound()
        val path = (u.path ?: "").trimStart('/')
        var data = b.read(path) ?: return notFound()
        val mime = b.mimeOf(path)
        if (b.isSpineDoc(path) && (mime.contains("html") || mime.contains("xml"))) {
            data = Paginator.injectStyle(data, Paginator.styleTag(style, cssW, cssH))
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))
    }

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun onResume() {
        super.onResume()
        web?.onResume()
        web?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // 离开阅读器时暂停 WebView 的 JS 定时器，避免书内脚本在后台耗电
        web?.onPause()
        web?.pauseTimers()
    }

    override fun onDestroy() {
        web?.let {
            it.stopLoading()
            (it.parent as? FrameLayout)?.removeView(it)
            it.destroy()
        }
        web = null
        book?.close()
        book = null
        super.onDestroy()
    }
}

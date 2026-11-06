package com.manus.kavitaink.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.manus.kavitaink.Prefs
import com.manus.kavitaink.R
import com.manus.kavitaink.ui.fontName
import com.manus.kavitaink.core.EpubBook
import com.manus.kavitaink.core.FontStore
import com.manus.kavitaink.core.Paginator
import com.manus.kavitaink.core.error
import com.manus.kavitaink.ui.FontsActivity
import com.manus.kavitaink.ui.MATCH
import com.manus.kavitaink.ui.optionsDialog
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
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
        private const val FONT_PATH = "__eink/font/"
        private val FONT_STEPS = listOf(80, 90, 100, 110, 120, 135, 150, 175, 200)
        private val LINE_STEPS = listOf(0, 130, 150, 160, 180, 200)
    }

    override val novel: Boolean get() = true

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
    /** 当前字体各变体的文件，下标与 @font-face 地址末尾的序号对应 */
    @Volatile private var fontFiles: List<File> = emptyList()
    @Volatile private var cssW = 0
    @Volatile private var cssH = 0
    private var lastW = 0
    private var lastH = 0

    private val spineCount get() = book?.spine?.size ?: 0

    /** 按当前设置生成排版样式；选中的字体文件不存在时自动用回书籍默认字体 */
    private fun novelStyle(): Paginator.Style {
        var family: String? = null
        var faces: List<Paginator.FontFace> = emptyList()
        var files: List<File> = emptyList()
        when (val id = prefs.fontId) {
            Prefs.FONT_DEFAULT -> Unit
            Prefs.FONT_SERIF -> family = "serif"
            Prefs.FONT_SANS -> family = "sans-serif"
            else -> app.fonts.get(id)?.let { f ->
                files = f.variants.map { app.fonts.fileOf(f, it) }
                // 地址里带上字体 ID：换字体后 WebView 不会误用缓存的旧字体
                val key = f.id.filter { it.isLetterOrDigit() || it == '-' }
                faces = f.variants.mapIndexed { i, v -> Paginator.FontFace("$BASE$FONT_PATH$key/$i", v.style, v.weight) }
            }
        }
        fontFiles = files
        return Paginator.Style(
            marginX = prefs.marginX,
            marginTop = prefs.marginY,
            marginBottom = prefs.marginY,
            lineHeight = prefs.lineHeight,
            fontFamily = family,
            fontFaces = faces,
            forceBlack = prefs.forceBlack,
            bookStyle = prefs.bookStyle,
            textIndent = prefs.textIndent,
            paraSpacing = prefs.paraSpacing,
            justify = prefs.justify,
            letterSpacing = prefs.letterSpacing,
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun setup() {
        style = novelStyle()
        // 仅在可调试构建中允许 chrome://inspect 远程调试（正式包不受影响）
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
        val w = try {
            WebView(this)
        } catch (t: Throwable) {
            frame.post { showHint(getString(R.string.epub_no_webview)) }
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
        // 尺寸变化（旋转屏幕、显示/隐藏状态栏）后按新尺寸重新分页，停留在相同位置
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
                showHint(getString(R.string.epub_open_failed, x.error(err)))
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
            showHint(getString(R.string.epub_empty))
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
        style = novelStyle()
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
        if (spineCount == 0) "" else getString(R.string.epub_progress, page + 1, pageCount, spine + 1, spineCount, bookPercent())

    override fun statusPage(): String = if (spineCount == 0) "" else "${page + 1} / $pageCount"

    override fun statusPercent(): Int = if (spineCount == 0) -1 else bookPercent()

    /** 当前所在的目录项（最后一个起点不晚于当前书脊位置的条目） */
    override fun statusChapter(): String {
        val b = book ?: return chapter.title(x)
        var name: String? = null
        for (e in b.toc) {
            val i = b.spineIndexOf(e.path)
            if (i in 0..spine) name = e.display(x)
        }
        return name ?: chapter.title(x)
    }

    override fun savePosition() {
        if (book == null) return
        prefs.savePosition(chapter, "$spine:$page:$pageCount", bookPercent())
    }

    override fun buildMenu(rows: LinearLayout) {
        menuRow(
            getString(R.string.epub_toc) to ::showToc,
            getString(R.string.epub_font_smaller) to { step(FONT_STEPS, prefs.fontZoom, -1) { prefs.fontZoom = it } },
            getString(R.string.epub_font_larger) to { step(FONT_STEPS, prefs.fontZoom, +1) { prefs.fontZoom = it } },
        )
        menuRow(
            getString(R.string.epub_line_height, lineHeightName(prefs.lineHeight)) to {
                prefs.lineHeight = ReaderOptions.next(LINE_STEPS, prefs.lineHeight)
                relayout()
                rebuildMenu()
            },
            getString(R.string.epub_font, fontName(prefs.fontId)) to ::chooseFont,
            getString(R.string.epub_layout) to ::showLayout,
        )
    }

    private fun lineHeightName(v: Int): String =
        if (v == 0) getString(R.string.value_default) else String.format(Locale.getDefault(), "%.1f", v / 100.0)


    private fun step(list: List<Int>, cur: Int, dir: Int, set: (Int) -> Unit) {
        val i = list.indexOf(cur).let { if (it < 0) list.indexOf(100).coerceAtLeast(0) else it }
        val n = (i + dir).coerceIn(0, list.size - 1)
        if (n == i) return
        set(list[n])
        relayout()
        rebuildMenu()
    }

    /** 选择字体：书籍默认 / 系统衬线 / 系统无衬线 / 已导入的字体 / 管理字体… */
    private fun chooseFont() {
        val installed = app.fonts.list()
        val ids = listOf(Prefs.FONT_DEFAULT, Prefs.FONT_SERIF, Prefs.FONT_SANS) + installed.map { it.id }
        val names = listOf(getString(R.string.font_book_default), getString(R.string.font_serif), getString(R.string.font_sans)) +
            installed.map { it.name } + getString(R.string.font_manage)
        val cur = ids.indexOf(prefs.fontId).let { if (it < 0) 0 else it }
        choose(getString(R.string.font_choose), names.toTypedArray(), cur) { i ->
            if (i >= ids.size) {
                startActivity(Intent(this, FontsActivity::class.java))
                return@choose
            }
            prefs.fontId = ids[i]
            relayout()
            rebuildMenu()
        }
    }

    /** 排版设置：每一项点了立即重新排版 */
    private fun showLayout() {
        optionsDialog(getString(R.string.layout_title), ReaderOptions.layout(this, prefs) { relayout() })
    }

    private fun showToc() {
        val b = book ?: return
        val toc = b.toc
        if (toc.isEmpty()) {
            showHint(getString(R.string.epub_no_toc))
            return
        }
        val labels = toc.map { "\u3000".repeat(it.level.coerceAtMost(4)) + it.display(x) }.toTypedArray()
        var checked = 0
        toc.forEachIndexed { i, e -> if (b.spineIndexOf(e.path) in 0..spine) checked = i }
        android.app.AlertDialog.Builder(this)
            .setTitle(b.title.ifBlank { getString(R.string.epub_toc) })
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                val e = toc[which]
                val idx = b.spineIndexOf(e.path)
                if (idx < 0) {
                    showHint(getString(R.string.epub_toc_missing))
                    return@setSingleChoiceItems
                }
                hideMenu()
                load(idx, e.fragment?.let { Target.Anchor(it) } ?: Target.First)
                turned()
            }
            .setNegativeButton(R.string.close, null)
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
        if (path.startsWith(FONT_PATH)) return serveFont(path)
        var data = b.read(path) ?: return notFound()
        val mime = b.mimeOf(path)
        if (b.isSpineDoc(path) && (mime.contains("html") || mime.contains("xml"))) {
            data = Paginator.injectStyle(data, Paginator.styleTag(style, cssW, cssH))
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))
    }

    /** 已安装的字体：直接以文件流交给 WebView；允许缓存，翻到下一节时不必重新读取整个字体文件 */
    private fun serveFont(path: String): WebResourceResponse {
        val i = path.substringAfterLast('/').toIntOrNull() ?: return notFound()
        val f = fontFiles.getOrNull(i)?.takeIf { it.isFile } ?: return notFound()
        return WebResourceResponse(
            FontStore.mimeOf(f.name), null, 200, "OK",
            mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"),
            FileInputStream(f),
        )
    }

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun onResume() {
        super.onResume()
        web?.onResume()
        web?.resumeTimers()
        // 从字体管理页返回：字体可能被更换或删除
        if (ready && novelStyle() != style) relayout()
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

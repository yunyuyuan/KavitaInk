package com.manus.kavitaink.reader

import android.app.AlertDialog
import android.graphics.Bitmap
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.manus.kavitaink.R
import com.manus.kavitaink.core.Kind
import com.manus.kavitaink.core.Spreads
import com.manus.kavitaink.ui.MATCH
import com.manus.kavitaink.ui.optionsDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 漫画（逐页图片）与 PDF 阅读器。
 * 「一屏」= 一页或并排的两页（双页模式）；翻页、跳页、进度都以屏为单位，进度记录这一屏的第一页。
 */
class ImageReaderActivity : ReaderActivity() {

    private lateinit var view: PageView
    private var source: PageSource? = null
    /** 每一屏包含的页码（按阅读顺序） */
    private var units: List<IntArray> = emptyList()
    private var unit = 0
    /** 当前这一屏的第一页（用于保存进度、重新拼页后定位） */
    private var page = 0
    private var aspects: FloatArray? = null
    /** 解码缓存：key = 页码 * 2 + (双页半宽 ? 1 : 0)；只保留当前屏和下一屏，最多 4 张 */
    private val cache = HashMap<Int, Bitmap>()
    private val inflight = HashSet<Int>()
    private val failed = HashSet<Int>()
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor()
    private var gen = 0
    private var started = false
    /** 正在等待解码完成后显示的屏（-1 表示没有） */
    private var waiting = -1
    private var waitingFromEnd = false

    override val rtl: Boolean get() = prefs.mangaRtl
    override val novel: Boolean get() = false

    private val count: Int get() = source?.count ?: 0

    /** 当前是否并排显示两页 */
    private val spreadOn: Boolean
        get() = when (prefs.spreadMode) {
            1 -> view.width > view.height
            2 -> true
            else -> false
        }

    /** 页序是否右→左（第一页放右边） */
    private val orderRtl: Boolean
        get() = when (prefs.spreadOrder) {
            1 -> false
            2 -> true
            else -> prefs.mangaRtl
        }

    override fun setup() {
        // 裁边耗时日志：可调试构建，或测试时在外部存储目录放一个 timing.flag（只输出耗时，不改变任何行为）
        cropTimingLog = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            java.io.File(getExternalFilesDir(null), "timing.flag").exists()
        view = PageView(this)
        view.contrast = prefs.contrast
        applyViewPrefs()
        view.setOnTouchListener(touch)
        frame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        source = try {
            when (chapter.kind) {
                Kind.PDF -> PdfSource(chapter.pdfFile)
                Kind.ZIP -> RasterSource(ZipInput(chapter.zipFile))
                else -> RasterSource(FolderInput(chapter.pagesDir))
            }
        } catch (t: Throwable) {
            null
        }
        val n = count
        if (n == 0) {
            frame.post { showHint(getString(R.string.img_open_failed)) }
            return
        }
        page = (prefs.position(chapter).toIntOrNull() ?: 0).coerceIn(0, n - 1)
        // 尺寸变化（首次布局、旋转、显示/隐藏状态栏）：重新拼页，停留在当前页
        view.onSize = { _, _ -> relayout() }
    }

    private fun applyViewPrefs() {
        view.gap = prefs.spreadGap
        view.divider = prefs.spreadDivider
        view.dividerWidth = prefs.dividerWidth
        view.dividerColor = ReaderOptions.dividerColor(prefs.dividerColor)
    }

    /** 丢弃缓存，按当前设置重新拼页并显示包含 page 的那一屏 */
    private fun relayout() {
        gen++
        cache.clear()
        inflight.clear()
        failed.clear()
        waiting = -1
        applyViewPrefs()
        view.fitWidth = prefs.fitMode == 1 && !spreadOn
        val src = source ?: return
        if (!spreadOn) {
            units = Spreads.single(count)
            started = true
            show(Spreads.indexOf(units, page), false)
            return
        }
        val known = aspects
        if (known != null) {
            buildSpreads(known)
            return
        }
        // 首次进入双页：在解码线程里读出每页的宽高比（只读文件头，很快）
        val g = gen
        decoder.execute {
            val a = FloatArray(src.count) { runCatching { src.aspect(it) }.getOrDefault(0.7f) }
            runOnUiThread {
                if (g != gen || isFinishing || isDestroyed) return@runOnUiThread
                aspects = a
                buildSpreads(a)
            }
        }
    }

    private fun buildSpreads(a: FloatArray) {
        units = Spreads.build(a, prefs.spreadCover, prefs.spreadShift(chapter))
        started = true
        show(Spreads.indexOf(units, page), false)
    }

    private fun key(p: Int, half: Boolean) = p * 2 + if (half) 1 else 0

    private fun show(u: Int, fromEnd: Boolean) {
        if (units.isEmpty()) return
        unit = u.coerceIn(0, units.size - 1)
        page = units[unit][0]
        updateInfo()
        val ps = units[unit]
        val half = ps.size == 2
        val missing = ps.filter { !cache.containsKey(key(it, half)) }
        if (missing.isEmpty()) {
            waiting = -1
            display(fromEnd)
            return
        }
        waiting = unit
        waitingFromEnd = fromEnd
        missing.forEach { if (key(it, half) !in inflight) load(it, half) }
    }

    private fun display(fromEnd: Boolean) {
        val ps = units[unit]
        if (ps.size == 2) {
            val a = cache[key(ps[0], true)]
            val b = cache[key(ps[1], true)]
            when {
                a != null && b != null -> view.setSpread(a, b, !orderRtl)
                a != null -> view.setPage(a, fromEnd)
                b != null -> view.setPage(b, fromEnd)
            }
        } else {
            cache[key(ps[0], false)]?.let { view.setPage(it, fromEnd) }
        }
        afterShow()
    }

    private fun afterShow() {
        val keep = HashSet<Int>()
        for (u in listOf(unit, unit + 1)) {
            val ps = units.getOrNull(u) ?: continue
            ps.forEach { keep += key(it, ps.size == 2) }
        }
        cache.keys.filter { it !in keep }.forEach { cache.remove(it) }
        // 预解码下一屏
        units.getOrNull(unit + 1)?.let { ps ->
            val half = ps.size == 2
            ps.forEach { p -> val k = key(p, half); if (!cache.containsKey(k) && k !in inflight && k !in failed) load(p, half) }
        }
    }

    private fun load(p: Int, half: Boolean) {
        val src = source ?: return
        val k = key(p, half)
        inflight += k
        val g = gen
        val w = if (half) view.width / 2 else view.width
        val h = view.height
        val fitW = view.fitWidth && !half
        val crop = if (prefs.cropMargins) CropOpt(prefs.cropBlack) else null
        decoder.execute {
            val b = try {
                src.decode(p, w, h, fitW, crop)
            } catch (oom: OutOfMemoryError) {
                null
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (g != gen || isFinishing || isDestroyed) return@runOnUiThread
                inflight -= k
                if (b != null) cache[k] = b else failed += k
                if (waiting == unit && units.getOrNull(unit)?.contains(p) == true) {
                    val ps = units[unit]
                    val hv = ps.size == 2
                    val pending = ps.any { key(it, hv) in inflight }
                    if (!pending) {
                        waiting = -1
                        val bad = ps.filter { key(it, hv) in failed }
                        if (bad.size < ps.size) display(waitingFromEnd)
                        if (bad.isNotEmpty()) showHint(getString(R.string.img_page_failed, bad[0] + 1))
                    }
                }
            }
        }
    }

    override fun nextPage() {
        if (!started || count == 0) return
        if (view.stepForward()) { turned(); return }
        if (unit + 1 < units.size) {
            show(unit + 1, false)
            turned()
        } else {
            reachedEnd()
        }
    }

    override fun prevPage() {
        if (!started || count == 0) return
        if (view.stepBack()) { turned(); return }
        if (unit > 0) {
            show(unit - 1, true)
            turned()
        } else {
            reachedStart()
        }
    }

    override fun statusPage(): String {
        val n = count
        if (n == 0 || units.isEmpty()) return ""
        val ps = units[unit]
        return if (ps.size == 2) "${ps[0] + 1}–${ps[1] + 1} / $n" else "${ps[0] + 1} / $n"
    }

    override fun statusPercent(): Int {
        val n = count
        if (n == 0 || units.isEmpty()) return -1
        return ((units[unit].last() + 1) * 100) / n
    }

    override fun progressText(): String = statusPage()

    override fun savePosition() {
        val n = count
        if (n == 0 || units.isEmpty()) return
        prefs.savePosition(chapter, page.toString(), ((units[unit].last() + 1) * 100) / n)
    }

    override fun buildMenu(rows: LinearLayout) {
        menuRow(
            getString(R.string.img_jump) to ::askJump,
            getString(R.string.img_direction, getString(if (prefs.mangaRtl) R.string.dir_rtl else R.string.dir_ltr)) to {
                prefs.mangaRtl = !prefs.mangaRtl
                // 页序「跟随阅读方向」时，左右两页要交换
                if (spreadOn && prefs.spreadOrder == 0) display(false)
                rebuildMenu()
            },
        )
        val zoom = menuRow(
            getString(R.string.img_zoom, getString(if (prefs.fitMode == 1) R.string.fit_width else R.string.fit_screen)) to {
                prefs.fitMode = if (prefs.fitMode == 1) 0 else 1
                relayout()
                rebuildMenu()
            },
            getString(R.string.img_contrast, getString(if (view.contrast) R.string.contrast_high else R.string.contrast_orig)) to {
                prefs.contrast = !prefs.contrast
                view.contrast = prefs.contrast
                rebuildMenu()
            },
        )
        // 双页模式只用「适应屏幕」
        if (spreadOn) zoom[0].disable()
        val shift = prefs.spreadShift(chapter)
        val third = menuRow(
            getString(R.string.img_spread, ReaderOptions.spreadModeName(this, prefs.spreadMode)) to ::showSpread,
            getString(R.string.label_value, getString(R.string.spread_shift), getString(if (shift) R.string.on else R.string.off)) to {
                prefs.setSpreadShift(chapter, !shift)
                relayout()
                rebuildMenu()
            },
            getString(R.string.img_crop, ReaderOptions.cropName(this, prefs)) to {
                // 关 → 裁白边 → 裁白边和黑边 → 关
                when {
                    !prefs.cropMargins -> { prefs.cropMargins = true; prefs.cropBlack = false }
                    !prefs.cropBlack -> prefs.cropBlack = true
                    else -> { prefs.cropMargins = false; prefs.cropBlack = false }
                }
                source?.clearCrop()
                relayout()
                rebuildMenu()
            },
        )
        if (!spreadOn) third[1].disable()
    }

    private fun showSpread() {
        optionsDialog(getString(R.string.img_layout_title), ReaderOptions.spread(this, prefs) { relayout() }) { rebuildMenu() }
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
            .setTitle(R.string.img_jump_title)
            .setView(input)
            .setPositiveButton(R.string.img_jump_go) { _, _ ->
                val p = input.text.toString().toIntOrNull()
                if (p != null) {
                    hideMenu()
                    // 跳到包含第 p 页的那一屏
                    show(Spreads.indexOf(units, (p - 1).coerceIn(0, n - 1)), false)
                    turned()
                }
            }
            .setNegativeButton(R.string.cancel, null)
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

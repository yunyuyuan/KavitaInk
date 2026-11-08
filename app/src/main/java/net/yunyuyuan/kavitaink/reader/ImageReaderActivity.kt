package net.yunyuyuan.kavitaink.reader

import android.app.AlertDialog
import android.graphics.Bitmap
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import net.yunyuyuan.kavitaink.R
import net.yunyuyuan.kavitaink.core.Kind
import net.yunyuyuan.kavitaink.core.Spreads
import net.yunyuyuan.kavitaink.core.Split
import net.yunyuyuan.kavitaink.ui.MATCH
import net.yunyuyuan.kavitaink.ui.optionsDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 漫画（逐页图片）与 PDF 阅读器。
 * 「一屏」= 一页或并排的两页（双页模式）；翻页、跳页、进度都以屏为单位，进度记录这一屏的第一页。
 * 拆分跨页（竖屏）：宽页按阅读顺序分两屏显示，每屏只解码那一半；进度另外记下停在哪一半。
 */
class ImageReaderActivity : ReaderActivity() {
    private companion object {
        /** show() 的 wantPart：拆分页显示后半（往回翻时） */
        const val LAST = -1
    }

    private lateinit var view: PageView
    private var source: PageSource? = null
    /** 每一屏包含的页码（按阅读顺序） */
    private var units: List<IntArray> = emptyList()
    private var unit = 0
    /** 当前这一屏的第一页（用于保存进度、重新拼页后定位） */
    private var page = 0
    /** 拆分跨页时停在这一页的哪一半：0 = 前半（或整页），1 = 后半 */
    private var part = 0
    /** 已知的每页宽高比（NaN = 还没读）；拆分跨页翻到某页时才读，双页模式一次读全 */
    private var known = FloatArray(0)
    private var allKnown = false
    private val aspectPending = HashSet<Int>()
    /**
     * 解码缓存：key = 页码 * 4 + 方式（0 整页，1 双页半宽，2 左半，3 右半）；
     * 只保留当前屏和下一屏（拆分页的两半都留着，翻回前半不必重解）
     */
    private val cache = HashMap<Int, Bitmap>()
    private val inflight = HashSet<Int>()
    private val failed = HashSet<Int>()
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor()
    private var gen = 0
    private var started = false
    /** 当前屏还在等宽高比或解码结果，到了就显示 */
    private var waiting = false
    private var waitingPart = 0
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

    /** 页序是否右→左（第一页放右边）；拆分跨页时也决定先看哪一半 */
    private val orderRtl: Boolean
        get() = when (prefs.spreadOrder) {
            1 -> false
            2 -> true
            else -> prefs.mangaRtl
        }

    /** 拆分跨页只在竖屏生效：横屏时宽页整张显示正好铺满 */
    private val splitOn: Boolean
        get() = prefs.splitWide && view.width in 1 until view.height

    private fun aspectOf(p: Int): Float? = known.getOrNull(p)?.takeUnless { it.isNaN() }

    /** 第 u 屏可能需要拆：拆分开着、这一屏只有一页、不是封面 */
    private fun candidate(u: Int): Boolean {
        val ps = units.getOrNull(u) ?: return false
        return splitOn && ps.size == 1 && ps[0] > 0
    }

    /** 第 u 屏是否拆成两半；宽高比还没读到时返回 null */
    private fun isSplit(u: Int): Boolean? {
        if (!candidate(u)) return false
        val a = aspectOf(units[u][0]) ?: return null
        return Split.splittable(units[u][0], a)
    }

    private fun key(p: Int, mode: Int) = p * 4 + mode

    /** 显示第 u 屏的第 pt 半需要的位图：(页码, 方式) */
    private fun modesOf(u: Int, pt: Int): List<Pair<Int, Int>> {
        val ps = units[u]
        if (ps.size == 2) return ps.map { it to 1 }
        if (isSplit(u) == true) return listOf(ps[0] to 1 + Split.side(pt, orderRtl))
        return listOf(ps[0] to 0)
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
        val pos = Split.decode(prefs.position(chapter))
        page = pos.first.coerceIn(0, n - 1)
        part = pos.second
        known = FloatArray(n) { Float.NaN }
        // 尺寸变化（首次布局、旋转、显示/隐藏状态栏）：重新拼页，停留在当前页
        view.onSize = { _, _ -> relayout() }
    }

    private fun applyViewPrefs() {
        view.gap = prefs.spreadGap
        view.divider = prefs.spreadDivider
        view.dividerWidth = prefs.dividerWidth
        view.dividerColor = ReaderOptions.dividerColor(prefs.dividerColor)
    }

    /** 丢弃缓存，按当前设置重新拼页并显示包含 page 的那一屏（拆分页停在原来那一半） */
    private fun relayout() {
        gen++
        cache.clear()
        inflight.clear()
        failed.clear()
        aspectPending.clear()
        waiting = false
        applyViewPrefs()
        view.fitWidth = prefs.fitMode == 1 && !spreadOn
        val src = source ?: return
        if (!spreadOn) {
            units = Spreads.single(count)
            started = true
            show(Spreads.indexOf(units, page), part, false)
            return
        }
        if (allKnown) {
            buildSpreads()
            return
        }
        // 首次进入双页：在解码线程里读出每页的宽高比（只读文件头，很快）
        val g = gen
        decoder.execute {
            val a = FloatArray(src.count) { runCatching { src.aspect(it) }.getOrDefault(0.7f) }
            runOnUiThread {
                if (g != gen || isFinishing || isDestroyed) return@runOnUiThread
                known = a
                allKnown = true
                buildSpreads()
            }
        }
    }

    private fun buildSpreads() {
        units = Spreads.build(known, prefs.spreadCover, prefs.spreadShift(chapter))
        started = true
        show(Spreads.indexOf(units, page), part, false)
    }

    /**
     * 显示第 u 屏。wantPart：拆分页显示哪一半（LAST = 后半，用于往回翻）；不拆的屏忽略。
     * 还不知道这一页是不是宽页时，先在解码线程读出尺寸再显示（ZIP 读到的字节会留给紧接着的解码用）。
     */
    private fun show(u: Int, wantPart: Int, fromEnd: Boolean) {
        if (units.isEmpty()) return
        unit = u.coerceIn(0, units.size - 1)
        page = units[unit][0]
        waitingPart = wantPart
        waitingFromEnd = fromEnd
        val split = isSplit(unit)
        if (split == null) {
            waiting = true
            requestAspect(page)
            return
        }
        part = if (split) (if (wantPart == LAST) 1 else wantPart.coerceIn(0, 1)) else 0
        updateInfo()
        val missing = modesOf(unit, part).filter { (p, m) -> !cache.containsKey(key(p, m)) }
        if (missing.isEmpty()) {
            waiting = false
            display(fromEnd)
            return
        }
        waiting = true
        missing.forEach { (p, m) -> if (key(p, m) !in inflight) load(p, m) }
    }

    private fun requestAspect(p: Int) {
        val src = source ?: return
        if (p in aspectPending || aspectOf(p) != null) return
        aspectPending += p
        val g = gen
        decoder.execute {
            val a = runCatching { src.aspect(p) }.getOrDefault(0.7f)
            runOnUiThread {
                if (g != gen || isFinishing || isDestroyed) return@runOnUiThread
                aspectPending -= p
                if (p < known.size) known[p] = a
                // 正在等这一页：现在知道拆不拆了，接着显示；否则是预读，接着预解码
                if (waiting && units.getOrNull(unit)?.getOrNull(0) == p && isSplit(unit) != null) {
                    show(unit, waitingPart, waitingFromEnd)
                } else if (!waiting) {
                    prefetch()
                }
            }
        }
    }

    private fun display(fromEnd: Boolean) {
        val ps = units[unit]
        if (ps.size == 2) {
            val a = cache[key(ps[0], 1)]
            val b = cache[key(ps[1], 1)]
            when {
                a != null && b != null -> view.setSpread(a, b, !orderRtl)
                a != null -> view.setPage(a, fromEnd)
                b != null -> view.setPage(b, fromEnd)
            }
        } else {
            val (p, m) = modesOf(unit, part)[0]
            cache[key(p, m)]?.let { view.setPage(it, fromEnd) }
        }
        afterShow()
    }

    /** 下一屏：(屏, 第几半)；已经是最后一屏时返回 null */
    private fun nextScreen(): Pair<Int, Int>? {
        if (part == 0 && isSplit(unit) == true) return unit to 1
        return if (unit + 1 < units.size) (unit + 1) to 0 else null
    }

    private fun afterShow() {
        val keep = HashSet<Int>()
        modesOf(unit, part).forEach { (p, m) -> keep += key(p, m) }
        if (isSplit(unit) == true) { keep += key(page, 2); keep += key(page, 3) }
        nextScreen()?.let { (u, pt) -> if (isSplit(u) != null) modesOf(u, pt).forEach { (p, m) -> keep += key(p, m) } }
        cache.keys.filter { it !in keep }.forEach { cache.remove(it) }
        prefetch()
    }

    /** 预解码下一屏（拆分页的后半，或下一页） */
    private fun prefetch() {
        val (u, pt) = nextScreen() ?: return
        if (isSplit(u) == null) { requestAspect(units[u][0]); return }
        modesOf(u, pt).forEach { (p, m) ->
            val k = key(p, m)
            if (!cache.containsKey(k) && k !in inflight && k !in failed) load(p, m)
        }
    }

    private fun load(p: Int, mode: Int) {
        val src = source ?: return
        val k = key(p, mode)
        inflight += k
        val g = gen
        val w = if (mode == 1) view.width / 2 else view.width
        val h = view.height
        val fitW = view.fitWidth && mode != 1
        val side = if (mode >= 2) mode - 1 else Split.WHOLE
        val crop = if (prefs.cropMargins) CropOpt(prefs.cropBlack) else null
        decoder.execute {
            val b = try {
                src.decode(p, w, h, fitW, crop, side)
            } catch (oom: OutOfMemoryError) {
                null
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (g != gen || isFinishing || isDestroyed) return@runOnUiThread
                inflight -= k
                if (b != null) cache[k] = b else failed += k
                if (waiting) tryDisplay()
            }
        }
    }

    /** 当前屏需要的位图都解码完了（或失败了）就显示 */
    private fun tryDisplay() {
        if (isSplit(unit) == null) return
        val need = modesOf(unit, part).map { (p, m) -> key(p, m) }
        if (need.any { it in inflight || (!cache.containsKey(it) && it !in failed) }) return
        waiting = false
        val bad = need.filter { it in failed }
        if (bad.size < need.size) display(waitingFromEnd)
        if (bad.isNotEmpty()) showHint(getString(R.string.img_page_failed, bad[0] / 4 + 1))
    }

    override fun nextPage() {
        if (!started || count == 0) return
        if (view.stepForward()) { turned(); return }
        val next = nextScreen()
        if (next != null) {
            show(next.first, next.second, false)
            turned()
        } else {
            reachedEnd()
        }
    }

    override fun prevPage() {
        if (!started || count == 0) return
        if (view.stepBack()) { turned(); return }
        when {
            // 拆分页的后半 → 前半
            part == 1 && isSplit(unit) == true -> { show(unit, 0, true); turned() }
            // 上一屏是拆分页时落在它的后半
            unit > 0 -> { show(unit - 1, LAST, true); turned() }
            else -> reachedStart()
        }
    }

    override fun statusPage(): String {
        val n = count
        if (n == 0 || units.isEmpty()) return ""
        val ps = units[unit]
        val base = if (ps.size == 2) "${ps[0] + 1}–${ps[1] + 1} / $n" else "${ps[0] + 1} / $n"
        return if (isSplit(unit) == true) "$base (${part + 1}/2)" else base
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
        prefs.savePosition(chapter, Split.encode(page, part), ((units[unit].last() + 1) * 100) / n)
    }

    override fun buildMenu(rows: LinearLayout) {
        menuRow(
            getString(R.string.img_jump) to ::askJump,
            getString(R.string.img_direction, getString(if (prefs.mangaRtl) R.string.dir_rtl else R.string.dir_ltr)) to {
                prefs.mangaRtl = !prefs.mangaRtl
                // 页序「跟随阅读方向」时，左右两页要交换；拆分页的前半换成另一侧
                if (prefs.spreadOrder == 0 && (spreadOn || isSplit(unit) == true)) show(unit, part, false)
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
            getString(R.string.label_value, getString(R.string.split_wide), getString(if (prefs.splitWide) R.string.on else R.string.off)) to {
                prefs.splitWide = !prefs.splitWide
                relayout()
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
                    // 跳到包含第 p 页的那一屏（拆分页从前半开始）
                    show(Spreads.indexOf(units, (p - 1).coerceIn(0, n - 1)), 0, false)
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

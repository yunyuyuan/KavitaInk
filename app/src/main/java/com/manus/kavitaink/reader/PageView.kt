package com.manus.kavitaink.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import com.manus.kavitaink.core.LumSource
import com.manus.kavitaink.core.RawLum
import com.manus.kavitaink.core.Split
import com.manus.kavitaink.core.ZipPages
import com.manus.kavitaink.core.Trim
import java.io.Closeable
import java.io.File
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 裁边选项：null = 不裁 */
data class CropOpt(val black: Boolean)

interface PageSource : Closeable {
    val count: Int
    /** 第 index 页的宽高比（宽 / 高），读不到时返回 0.7（按普通竖页处理） */
    fun aspect(index: Int): Float
    /** side：Split.WHOLE 整页，Split.LEFT / Split.RIGHT 只解码左半 / 右半（拆分跨页） */
    fun decode(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean, crop: CropOpt?, side: Int = Split.WHOLE): Bitmap?
    /** 裁边设置变化后清掉缓存的裁切区域 */
    fun clearCrop()
}

private const val MAX_PIXELS = 8_000_000L
/** 检测白边用的缩小图最长边：漫画页在已解码的整页上按块取平均，不超过 400 px；PDF 单独渲染到 256 px */
private const val PROBE_MAX = 400
private const val PROBE_PDF = 256

/** 只在可调试构建里打开：记录每页裁边的额外耗时，用于性能验收 */
@Volatile
var cropTimingLog = false

private fun ms(fromNanos: Long, toNanos: Long) = (toNanos - fromNanos) / 1_000_000.0

/** 调试构建：记录整页解码（含裁边）耗时，作为裁边额外耗时的对照 */
private inline fun timedDecode(index: Int, crop: CropOpt?, block: () -> Bitmap?): Bitmap? {
    if (!cropTimingLog) return block()
    val t0 = System.nanoTime()
    val b = block()
    android.util.Log.d("KavitaInk", "decode page=$index ms=${ms(t0, System.nanoTime())} crop=${crop != null}")
    return b
}

/** 每页的裁切区域（按比例存，和解码分辨率无关）；空数组 = 不需要裁 */
private class CropCache {
    private val map = Collections.synchronizedMap(HashMap<Int, FloatArray>())
    private var black = false

    /** 已缓存的裁切区域；「同时裁黑边」变了就整体作废 */
    fun peek(i: Int, opt: CropOpt): FloatArray? {
        synchronized(this) {
            if (opt.black != black) {
                map.clear()
                black = opt.black
            }
        }
        return map[i]
    }

    fun put(i: Int, v: FloatArray) {
        map[i] = v
    }

    fun get(i: Int, opt: CropOpt, compute: () -> FloatArray): FloatArray {
        peek(i, opt)?.let { return it }
        val t0 = if (cropTimingLog) System.nanoTime() else 0L
        val v = runCatching(compute).getOrDefault(FloatArray(0))
        if (cropTimingLog) android.util.Log.d("KavitaInk", "crop page=$i ms=${ms(t0, System.nanoTime())} cut=${v.isNotEmpty()}")
        put(i, v)
        return v
    }

    fun clear() = map.clear()
}

/**
 * 位图原始像素的一份拷贝（Bitmap.copyPixelsToBuffer：纯内存拷贝，不经过 Skia）。只支持 RGB_565 / ARGB_8888。
 * 检测和截取都在这份拷贝上做：Skia 的缩放 / 截取在没有硬件加速的模拟器上非常慢，而这里只有内存拷贝和整数运算。
 */
private class RawPixels private constructor(
    private val config: Bitmap.Config,
    private val w: Int,
    private val h: Int,
    private val stride: Int,
    private val s16: ShortArray?,
    private val s32: IntArray?,
) {
    companion object {
        fun of(b: Bitmap): RawPixels? = when (b.config) {
            Bitmap.Config.RGB_565 -> {
                val stride = b.rowBytes / 2
                val a = ShortArray(stride * b.height)
                b.copyPixelsToBuffer(ShortBuffer.wrap(a))
                RawPixels(Bitmap.Config.RGB_565, b.width, b.height, stride, a, null)
            }
            Bitmap.Config.ARGB_8888 -> {
                val stride = b.rowBytes / 4
                val a = IntArray(stride * b.height)
                b.copyPixelsToBuffer(IntBuffer.wrap(a))
                RawPixels(Bitmap.Config.ARGB_8888, b.width, b.height, stride, null, a)
            }
            else -> null
        }
    }

    /** 检测用的缩小图（按块取平均，最长边不超过 maxSide），亮度按需计算 */
    fun lum(maxSide: Int): LumSource {
        val f = RawLum.blockFor(w, h, maxSide)
        return if (s16 != null) RawLum.rgb565(s16, stride, w, h, f) else RawLum.rgba8888(s32!!, stride, w, h, f)
    }

    /** 检测结果（按比例）；空数组 = 不需要裁 */
    fun detectFrac(maxSide: Int, black: Boolean): FloatArray {
        val src = lum(maxSide)
        val b = Trim.detect(src, black) ?: return FloatArray(0)
        return floatArrayOf(b.left.toFloat() / src.w, b.top.toFloat() / src.h, b.right.toFloat() / src.w, b.bottom.toFloat() / src.h)
    }

    /** 截出 (l, t, cw, ch) 的新位图：在数组里原地把每行挪到前面，再整块拷进新位图。之后这份拷贝不能再用。 */
    fun crop(l: Int, t: Int, cw: Int, ch: Int): Bitmap {
        val out = Bitmap.createBitmap(cw, ch, config)
        if (s16 != null) {
            val ds = out.rowBytes / 2
            for (r in 0 until ch) System.arraycopy(s16, (t + r) * stride + l, s16, r * ds, cw)
            out.copyPixelsFromBuffer(ShortBuffer.wrap(s16, 0, ds * ch))
        } else {
            val a = s32!!
            val ds = out.rowBytes / 4
            for (r in 0 until ch) System.arraycopy(a, (t + r) * stride + l, a, r * ds, cw)
            out.copyPixelsFromBuffer(IntBuffer.wrap(a, 0, ds * ch))
        }
        return out
    }
}

/**
 * 预检（见 Trim.outerEdgesAreContent）：只用 getPixels 取最外侧的四条细边（各一个块宽），
 * 四条都是内容（满版画面）就不必拷贝整页像素。按块对齐的方式和 RawPixels.lum 完全一致。
 */
private fun outerEdgesAreContent(b: Bitmap, f: Int, black: Boolean): Boolean {
    val w = b.width
    val h = b.height
    val tb = min(f, h)
    val bb = h - ((h + f - 1) / f - 1) * f // 最后一个块的行数
    val lb = min(f, w)
    val rb = w - ((w + f - 1) / f - 1) * f
    return Trim.outerEdgesAreContent(
        { RawLum.argb(IntArray(w * tb).also { b.getPixels(it, 0, w, 0, 0, w, tb) }, w, w, tb, f) },
        { RawLum.argb(IntArray(w * bb).also { b.getPixels(it, 0, w, 0, h - bb, w, bb) }, w, w, bb, f) },
        { RawLum.argb(IntArray(lb * h).also { b.getPixels(it, 0, lb, 0, 0, lb, h) }, lb, lb, h, f) },
        { RawLum.argb(IntArray(rb * h).also { b.getPixels(it, 0, rb, w - rb, 0, rb, h) }, rb, rb, h, f) },
        black,
    )
}

/** 原图坐标里的区域 → 已解码位图（可能按 inSampleSize 缩小过）里的 (l, t, w, h) */
private fun decodedRect(region: Rect, bw: Int, bh: Int, fw: Int, fh: Int): IntArray {
    val sx = bw.toFloat() / fw
    val sy = bh.toFloat() / fh
    val l = (region.left * sx).toInt().coerceIn(0, bw - 1)
    val t = (region.top * sy).toInt().coerceIn(0, bh - 1)
    val w = kotlin.math.ceil(region.width() * sx).toInt().coerceIn(1, bw - l)
    val h = kotlin.math.ceil(region.height() * sy).toInt().coerceIn(1, bh - t)
    return intArrayOf(l, t, w, h)
}

/** 一页图片的来源：逐页下载的图片文件，或 CBZ / ZIP 原文件里的条目 */
interface PageInput : Closeable {
    val count: Int
    fun decode(index: Int, o: BitmapFactory.Options): Bitmap?
    fun regionDecoder(index: Int): BitmapRegionDecoder?
}

/** 逐页下载的图片文件夹 */
class FolderInput(dir: File) : PageInput {
    private val files: List<File> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && !it.name.endsWith(".part") }
        .sortedBy { it.name }

    override val count: Int get() = files.size

    override fun decode(index: Int, o: BitmapFactory.Options): Bitmap? =
        files.getOrNull(index)?.let { BitmapFactory.decodeFile(it.path, o) }

    @Suppress("DEPRECATION")
    override fun regionDecoder(index: Int): BitmapRegionDecoder? =
        files.getOrNull(index)?.let { BitmapRegionDecoder.newInstance(it.path, false) }

    override fun close() {}
}

/** CBZ / ZIP 原文件：不解压到磁盘，按需读出一页的字节（最近读过的两页留在内存，先读尺寸再解码不必读两次） */
class ZipInput(file: File) : PageInput {
    private val zip = ZipPages.open(file)
    private val recent = object : LinkedHashMap<Int, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > 2
    }

    override val count: Int get() = zip.count

    private fun bytes(i: Int): ByteArray? {
        if (i !in 0 until zip.count) return null
        synchronized(recent) { recent[i]?.let { return it } }
        val b = zip.bytes(i)
        synchronized(recent) { recent[i] = b }
        return b
    }

    override fun decode(index: Int, o: BitmapFactory.Options): Bitmap? =
        bytes(index)?.let { BitmapFactory.decodeByteArray(it, 0, it.size, o) }

    @Suppress("DEPRECATION")
    override fun regionDecoder(index: Int): BitmapRegionDecoder? =
        bytes(index)?.let { BitmapRegionDecoder.newInstance(it, 0, it.size, false) }

    override fun close() {
        synchronized(recent) { recent.clear() }
        zip.close()
    }
}

/** 位图页面（漫画）：解码、按需裁白边；拆分跨页时只解码左半或右半 */
class RasterSource(private val input: PageInput) : PageSource {
    private val crops = CropCache()

    override val count: Int get() = input.count

    private fun bounds(index: Int): BitmapFactory.Options? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.decode(index, o)
        return o.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    override fun aspect(index: Int): Float {
        val o = bounds(index) ?: return 0.7f
        return o.outWidth.toFloat() / o.outHeight
    }

    override fun clearCrop() = crops.clear()

    override fun decode(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean, crop: CropOpt?, side: Int): Bitmap? =
        timedDecode(index, crop) { decodeImpl(index, reqW, reqH, fitWidth, crop, side) }

    private fun decodeImpl(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean, crop: CropOpt?, side: Int): Bitmap? {
        if (index !in 0 until count) return null
        val o = bounds(index) ?: return null
        val fw = o.outWidth
        val fh = o.outHeight
        // 拆分跨页：只看这一半（null = 整页）
        val base = Split.half(fw, fh, side)?.let { Rect(it[0], it[1], it[2], it[3]) }
        if (crop == null) return decodeRegion(index, fw, fh, base, reqW, reqH, fitWidth)
        // 左半、右半、整页各自检测白边：外侧白边宽，靠中缝那侧通常没有
        val ck = index * 3 + side
        val cached = crops.peek(ck, crop)
            ?: return decodeAndDetect(ck, index, fw, fh, base, reqW, reqH, fitWidth, crop)
        // 翻回这一页：裁切区域已知，只解码内容区域
        return decodeRegion(index, fw, fh, regionOf(cached, fw, fh) ?: base, reqW, reqH, fitWidth)
    }

    private fun regionOf(frac: FloatArray, fw: Int, fh: Int): Rect? =
        if (frac.size == 4) Rect(
            (frac[0] * fw).toInt(), (frac[1] * fh).toInt(),
            min(fw, kotlin.math.ceil(frac[2] * fw).toInt()), min(fh, kotlin.math.ceil(frac[3] * fh).toInt()),
        ).takeIf { it.width() > 0 && it.height() > 0 } else null

    private fun sampleSize(rw: Int, rh: Int, reqW: Int, reqH: Int, fitWidth: Boolean): Int {
        val scale = if (fitWidth) reqW.toFloat() / rw else min(reqW.toFloat() / rw, reqH.toFloat() / rh)
        var s = 1
        while (scale * s * 2 <= 1f) s *= 2
        while ((rw / s).toLong() * (rh / s) > MAX_PIXELS) s *= 2
        return s
    }

    private fun opts(s: Int) = BitmapFactory.Options().apply {
        inSampleSize = s
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    /**
     * 只解码需要的部分（内容区域、半页，或整页）：按这部分的尺寸决定缩小倍数，
     * 所以半页和普通页一样清晰，内存也和普通页一样。
     */
    private fun decodeRegion(f: Int, fw: Int, fh: Int, region: Rect?, reqW: Int, reqH: Int, fitWidth: Boolean): Bitmap? {
        val s = sampleSize(region?.width() ?: fw, region?.height() ?: fh, reqW, reqH, fitWidth)
        if (region == null) return input.decode(f, opts(s))
        val partial = runCatching {
            val d = input.regionDecoder(f) ?: return@runCatching null
            try { d.decodeRegion(region, opts(s)) } finally { d.recycle() }
        }.getOrNull()
        if (partial != null) return partial
        // 退路：解码整页再截取；整页比这部分大，要另外保证不超过像素上限
        var sf = s
        while ((fw / sf).toLong() * (fh / sf) > MAX_PIXELS) sf *= 2
        val full = input.decode(f, opts(sf)) ?: return null
        return cropBitmap(full, region, fw, fh)
    }

    /** 从整页位图里截出内容区域，并释放整页位图（区域解码失败时的退路） */
    private fun cropBitmap(full: Bitmap, region: Rect, fw: Int, fh: Int): Bitmap {
        val r = decodedRect(region, full.width, full.height, fw, fh)
        val out = Bitmap.createBitmap(full, r[0], r[1], r[2], r[3])
        if (out !== full) full.recycle()
        return out
    }

    /**
     * 第一次打开这一页（裁切区域未知）：照常解码整页（或半页）→ 拷出原始像素 → 在按块平均的缩小图上检测 → 直接截出内容区域。
     * 不为了检测再读一遍文件，也不经过 Skia，所以额外耗时只有「拷像素 + 检测 + 截取」。
     * 只有裁掉的部分多到截出来的分辨率不够时，才改为只解码内容区域（大图、裁得多时才会发生）。
     */
    private fun decodeAndDetect(
        ck: Int, f: Int, fw: Int, fh: Int, base: Rect?, reqW: Int, reqH: Int, fitWidth: Boolean, crop: CropOpt,
    ): Bitmap? {
        val area = base ?: Rect(0, 0, fw, fh)
        val full = decodeRegion(f, fw, fh, base, reqW, reqH, fitWidth) ?: return null
        val bw = full.width
        val bh = full.height
        val t0 = System.nanoTime()
        // 满版画面（四条最外边都是内容）一定不裁：不拷贝整页像素
        val fullBleed = runCatching { outerEdgesAreContent(full, RawLum.blockFor(bw, bh, PROBE_MAX), crop.black) }.getOrDefault(false)
        val raw = if (fullBleed) null else runCatching { RawPixels.of(full) }.getOrNull()
        val t1 = System.nanoTime()
        val local = raw?.let { r -> runCatching { r.detectFrac(PROBE_MAX, crop.black) }.getOrNull() } ?: FloatArray(0)
        val t2 = System.nanoTime()
        // 检测结果相对这一部分 → 换算成相对整张原图后缓存
        val frac = Split.toFull(local, intArrayOf(area.left, area.top, area.right, area.bottom), fw, fh)
        crops.put(ck, frac)
        val region = regionOf(frac, fw, fh)
        var redecode = false
        val out = when {
            region == null || raw == null -> full
            // 已解码的分辨率够用：直接从像素拷贝里截取（按实际缩小倍数判断，退路解码时倍数可能更大）
            sampleSize(region.width(), region.height(), reqW, reqH, fitWidth) * 1.02f >= area.width().toFloat() / bw -> {
                val rel = Rect(region.left - area.left, region.top - area.top, region.right - area.left, region.bottom - area.top)
                val r = decodedRect(rel, bw, bh, area.width(), area.height())
                full.recycle()
                raw.crop(r[0], r[1], r[2], r[3])
            }
            // 裁得多、需要更高的分辨率：只解码内容区域
            else -> {
                redecode = true
                full.recycle()
                decodeRegion(f, fw, fh, region, reqW, reqH, fitWidth)
            }
        }
        if (cropTimingLog) {
            val t3 = System.nanoTime()
            android.util.Log.d(
                "KavitaInk",
                "crop page=$f ms=${ms(t0, t3)} read=${ms(t0, t1)} detect=${ms(t1, t2)} copy=${ms(t2, t3)} " +
                    "cut=${region != null} redecode=$redecode skip=$fullBleed size=${bw}x$bh side=${ck % 3}",
            )
        }
        return out
    }

    override fun close() = input.close()
}

/** 系统自带 PdfRenderer，无需第三方库；拆分跨页时只渲染左半或右半 */
class PdfSource(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val crops = CropCache()

    override val count: Int get() = renderer.pageCount

    @Synchronized
    override fun aspect(index: Int): Float {
        if (index !in 0 until renderer.pageCount) return 0.7f
        val p = renderer.openPage(index)
        try {
            return if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 0.7f
        } finally {
            p.close()
        }
    }

    override fun clearCrop() = crops.clear()

    @Synchronized
    override fun decode(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean, crop: CropOpt?, side: Int): Bitmap? =
        timedDecode(index, crop) { decodeImpl(index, reqW, reqH, fitWidth, crop, side) }

    private fun decodeImpl(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean, crop: CropOpt?, side: Int): Bitmap? {
        if (index !in 0 until renderer.pageCount) return null
        val p = renderer.openPage(index)
        try {
            val pw = p.width.toFloat()
            val ph = p.height.toFloat()
            // 这一次要显示的部分（页面坐标，点）：整页或半页
            val half = Split.half(p.width, p.height, side)
            val bx = half?.get(0)?.toFloat() ?: 0f
            val bw = half?.let { (it[2] - it[0]).toFloat() } ?: pw
            // 裁切比例相对这一部分
            val frac = if (crop != null) crops.get(index * 3 + side, crop) { probe(p, crop.black, bx, bw) } else FloatArray(0)
            val l = bx + if (frac.size == 4) frac[0] * bw else 0f
            val t = if (frac.size == 4) frac[1] * ph else 0f
            val rw = if (frac.size == 4) (frac[2] - frac[0]) * bw else bw
            val rh = if (frac.size == 4) (frac[3] - frac[1]) * ph else ph
            val scale = if (fitWidth) reqW / rw else min(reqW / rw, reqH / rh)
            var w = max(1, (rw * scale).toInt())
            var h = max(1, (rh * scale).toInt())
            val px = w.toLong() * h
            if (px > MAX_PIXELS) {
                val f = sqrt(MAX_PIXELS.toDouble() / px)
                w = max(1, (w * f).toInt())
                h = max(1, (h * f).toInt())
            }
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            // 页面坐标（点）→ 位图像素：只渲染需要的部分，按目标分辨率出图
            val m = Matrix()
            m.postTranslate(-l, -t)
            m.postScale(w / rw, h / rh)
            p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return b
        } finally {
            p.close()
        }
    }

    /** 把 [bx, bx + bw] 这一部分渲染成小图检测白边，结果相对这一部分 */
    private fun probe(p: PdfRenderer.Page, black: Boolean, bx: Float, bw: Float): FloatArray {
        val ph = p.height.toFloat()
        val k = PROBE_PDF / max(bw, ph)
        val w = max(8, (bw * k).toInt())
        val h = max(8, (ph * k).toInt())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            b.eraseColor(Color.WHITE)
            val m = Matrix()
            m.postTranslate(-bx, 0f)
            m.postScale(w / bw, h / ph)
            p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            RawPixels.of(b)?.detectFrac(PROBE_PDF, black) ?: FloatArray(0)
        } finally {
            b.recycle()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

/**
 * 页面视图：
 *  - 单页：适应屏幕 / 适应宽度；适应宽度时长图按 92% 屏高分段翻页（无滚动动画）
 *  - 双页：两页缩放到同一高度并排，整体适应屏幕，中缝间距与分隔线可配置
 * 使用软件图层，避免超长图超过 GPU 纹理尺寸上限而无法显示。
 */
class PageView(ctx: Context) : View(ctx) {
    private var first: Bitmap? = null
    private var second: Bitmap? = null
    /** 双页时 first 是否画在左边 */
    private var firstOnLeft = true

    var fitWidth = false
    var onSize: ((Int, Int) -> Unit)? = null
    var contrast = false
        set(v) {
            field = v
            paint.colorFilter = if (v) contrastFilter else null
            invalidate()
        }

    var gap = 8
    var divider = false
    var dividerWidth = 2
    var dividerColor = Color.BLACK

    private var offsetY = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val linePaint = Paint()
    private val dst = RectF()
    private val contrastFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0f)
        val c = 1.35f
        val t = (1f - c) * 128f
        postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.WHITE)
    }

    private val single get() = second == null

    private fun scaleOf(b: Bitmap): Float =
        if (fitWidth) width.toFloat() / b.width
        else min(width.toFloat() / b.width, height.toFloat() / b.height)

    private fun scaledHeight(b: Bitmap) = b.height * scaleOf(b)

    fun setPage(b: Bitmap, fromEnd: Boolean) {
        first = b
        second = null
        offsetY = if (fitWidth && fromEnd) max(0f, scaledHeight(b) - height) else 0f
        invalidate()
    }

    fun setSpread(a: Bitmap, b: Bitmap, aOnLeft: Boolean) {
        first = a
        second = b
        firstOnLeft = aOnLeft
        offsetY = 0f
        invalidate()
    }

    fun stepForward(): Boolean {
        val b = first ?: return false
        if (!fitWidth || !single) return false
        val sh = scaledHeight(b)
        if (offsetY + height >= sh - 2) return false
        offsetY = min(offsetY + height * 0.92f, sh - height)
        invalidate()
        return true
    }

    fun stepBack(): Boolean {
        if (first == null || !fitWidth || !single || offsetY <= 1f) return false
        offsetY = max(0f, offsetY - height * 0.92f)
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) onSize?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val a = first ?: return
        if (a.isRecycled) return
        val b = second
        if (b == null || b.isRecycled) {
            val s = scaleOf(a)
            val dw = a.width * s
            val dh = a.height * s
            val left = (width - dw) / 2f
            val top = if (fitWidth && dh > height) -offsetY else (height - dh) / 2f
            dst.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(a, null, dst, paint)
            return
        }
        val l = if (firstOnLeft) a else b
        val r = if (firstOnLeft) b else a
        // 分隔线比间距宽时，把间距放宽到线的粗细
        val g = if (divider) max(gap, dividerWidth) else gap
        val ln = l.width.toFloat() / l.height
        val rn = r.width.toFloat() / r.height
        val hh = min(height.toFloat(), (width - g) / (ln + rn)).coerceAtLeast(1f)
        val total = hh * (ln + rn) + g
        val x0 = (width - total) / 2f
        val y0 = (height - hh) / 2f
        dst.set(x0, y0, x0 + hh * ln, y0 + hh)
        canvas.drawBitmap(l, null, dst, paint)
        val rx = x0 + hh * ln + g
        dst.set(rx, y0, rx + hh * rn, y0 + hh)
        canvas.drawBitmap(r, null, dst, paint)
        if (divider && dividerWidth > 0) {
            val cx = x0 + hh * ln + g / 2f
            linePaint.color = dividerColor
            canvas.drawRect(cx - dividerWidth / 2f, y0, cx + dividerWidth / 2f, y0 + hh, linePaint)
        }
    }
}

package net.yunyuyuan.kavitaink.core

import kotlin.math.max
import kotlin.math.min

/** 内容区域（像素坐标，right / bottom 不含） */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top

    /** 按比例换算到另一尺寸的坐标系（检测用的小图 → 原图） */
    fun scale(fromW: Int, fromH: Int, toW: Int, toH: Int): Box {
        val sx = toW.toDouble() / fromW
        val sy = toH.toDouble() / fromH
        return Box(
            (left * sx).toInt().coerceIn(0, toW),
            (top * sy).toInt().coerceIn(0, toH),
            kotlin.math.ceil(right * sx).toInt().coerceIn(0, toW),
            kotlin.math.ceil(bottom * sy).toInt().coerceIn(0, toH),
        )
    }
}

/** 按需提供亮度（0~255）的一张图。裁边检测只读取四条边向内扫描到的那些行列。 */
abstract class LumSource(val w: Int, val h: Int) {
    /** 第 y 行的亮度写入 out[0 until w] */
    abstract fun row(y: Int, out: IntArray)

    /** 第 x 列第 [from, to) 行的亮度写入 out[from until to] */
    abstract fun col(x: Int, out: IntArray, from: Int, to: Int)
}

/** ARGB 像素（Bitmap.getPixels 的格式） */
class ArgbLum(private val px: IntArray, w: Int, h: Int) : LumSource(w, h) {
    override fun row(y: Int, out: IntArray) {
        var i = y * w
        for (x in 0 until w) {
            out[x] = Trim.lum(px[i])
            i++
        }
    }

    override fun col(x: Int, out: IntArray, from: Int, to: Int) {
        var i = from * w + x
        for (y in from until to) {
            out[y] = Trim.lum(px[i])
            i += w
        }
    }
}

/**
 * 解码后位图的原始像素（Bitmap.copyPixelsToBuffer 拷出来的，纯内存拷贝），按 f×f 的块取平均作为缩小图的亮度。
 * 等价于「先把图缩小到 1/f 再检测」（细线只会变灰、不会丢），但不经过 Skia 缩放，而且只计算用到的行列。
 *  - RGB_565：ShortArray，每个像素 RRRRRGGGGGGBBBBB
 *  - ARGB_8888：IntArray，内存里是 R、G、B、A 四个字节，按小端读成 Int 即 A<<24 | B<<16 | G<<8 | R
 * stride 是每行的像素数（rowBytes / 每像素字节数）。
 */
class RawLum private constructor(
    private val s16: ShortArray?,
    private val s32: IntArray?,
    private val stride: Int,
    private val fw: Int,
    private val fh: Int,
    f: Int,
    argbOrder: Boolean = false,
) : LumSource((fw + f - 1) / f, (fh + f - 1) / f) {
    companion object {
        fun rgb565(buf: ShortArray, stride: Int, fw: Int, fh: Int, f: Int) = RawLum(buf, null, stride, fw, fh, f)
        fun rgba8888(buf: IntArray, stride: Int, fw: Int, fh: Int, f: Int) = RawLum(null, buf, stride, fw, fh, f)

        /** Bitmap.getPixels 的 ARGB 格式（0xAARRGGBB：最低字节是 B） */
        fun argb(buf: IntArray, stride: Int, fw: Int, fh: Int, f: Int) = RawLum(null, buf, stride, fw, fh, f, argbOrder = true)

        /** 块边长：2 的幂，让缩小图的最长边不超过 maxSide */
        fun blockFor(fw: Int, fh: Int, maxSide: Int): Int {
            var f = 1
            while ((max(fw, fh) + f - 1) / f > maxSide) f *= 2
            return f
        }

    }

    private val f = f
    private val shift = Integer.numberOfTrailingZeros(f)
    private val sums = LongArray(max(w, h))

    // 32 位像素最低字节 / 第三个字节的亮度权重：RGBA 内存顺序是 R…B，ARGB 是 B…R
    private val wLo = if (argbOrder) 114L else 299L
    private val wHi = if (argbOrder) 299L else 114L

    /**
     * 原图第 yy 行、第 [x0, x1) 列的「亮度 ×1000」之和。
     * 每个像素只读一次内存，R/G/B 分量分别累加，最后才乘权重（没有查找表：在没有硬件加速的模拟器上，访存比算术贵得多）。
     * RGB565 的权重已经包含 5/6 位到 8 位的换算：纯白 = 31×2460 + 63×2376 + 31×938 = 255026 → 亮度 255。
     */
    private fun rowSum(yy: Int, x0: Int, x1: Int): Long {
        var i = yy * stride + x0
        val end = i + (x1 - x0)
        var r = 0
        var g = 0
        var b = 0
        val a16 = s16
        if (a16 != null) {
            while (i < end) {
                val v = a16[i].toInt()
                r += (v ushr 11) and 31
                g += (v ushr 5) and 63
                b += v and 31
                i++
            }
            return r * 2460L + g * 2376L + b * 938L
        }
        val a32 = s32!!
        while (i < end) {
            val c = a32[i]
            r += c and 0xFF
            g += (c ushr 8) and 0xFF
            b += (c ushr 16) and 0xFF
            i++
        }
        return r * wLo + g * 587L + b * wHi
    }

    /** 缩小图第 y 行：原图 f 行，每行按块累加 */
    override fun row(y: Int, out: IntArray) {
        java.util.Arrays.fill(sums, 0, w, 0L)
        val y0 = y * f
        val y1 = min(fh, y0 + f)
        val a16 = s16
        val a32 = s32
        // 逐行顺序读取，循环写在这里（每个块调用一次函数的开销在模拟器上不可忽略）
        for (yy in y0 until y1) {
            val base = yy * stride
            var x0 = 0
            for (bx in 0 until w) {
                val x1 = min(fw, x0 + f)
                var i = base + x0
                val end = base + x1
                var r = 0
                var g = 0
                var b = 0
                if (a16 != null) {
                    while (i < end) {
                        val v = a16[i].toInt()
                        r += (v ushr 11) and 31
                        g += (v ushr 5) and 63
                        b += v and 31
                        i++
                    }
                    sums[bx] += r * 2460L + g * 2376L + b * 938L
                } else {
                    while (i < end) {
                        val c = a32!![i]
                        r += c and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += (c ushr 16) and 0xFF
                        i++
                    }
                    sums[bx] += r * wLo + g * 587L + b * wHi
                }
                x0 = x1
            }
        }
        val rows = (y1 - y0).toLong()
        var x0 = 0
        for (bx in 0 until w) {
            val x1 = min(fw, x0 + f)
            out[bx] = (sums[bx] / (rows * (x1 - x0) * 1000)).toInt()
            x0 = x1
        }
    }

    /** 缩小图第 x 列的第 [from, to) 行 */
    override fun col(x: Int, out: IntArray, from: Int, to: Int) {
        val x0 = x * f
        val x1 = min(fw, x0 + f)
        val cols = (x1 - x0).toLong()
        for (by in from until to) {
            val y0 = by shl shift
            val y1 = min(fh, y0 + f)
            var sum = 0L
            for (yy in y0 until y1) sum += rowSum(yy, x0, x1)
            out[by] = (sum / ((y1 - y0) * cols * 1000)).toInt()
        }
    }
}

/**
 * 自动裁白边（可选同时裁黑边）。在缩小过的图上从四条边分别向内扫描：
 *  - 一行（列）里 99.5% 以上的像素亮度 > 235 算白边（裁黑边时 < 20 也算），遇到第一行（列）不是边就停
 *    —— 所以永远不会裁进画面：白底一直画到边缘的图，最多裁到第一处有内容的位置
 *  - 每条边最多裁掉对应尺寸的 25%；裁好后四周各留 1% 安全边距
 *  - 整页都是白色（或黑色）、或裁完面积不到原图 40% 时放弃
 * 纯 Kotlin，便于在 JVM 上测试。亮度通过 LumSource 按需读取：只读四条边向内扫描到的那些行列。
 */
object Trim {
    private const val WHITE = 235
    private const val BLACK = 20
    private const val EDGE_RATIO = 0.995
    private const val MAX_SIDE = 0.25
    private const val PAD = 0.01
    private const val MIN_AREA = 0.40

    /** 亮度 lums[from until to] 这一行（列）是不是「边」：99.5% 以上是白（或开启裁黑边时是黑） */
    fun isEdgeLine(lums: IntArray, from: Int, to: Int, black: Boolean): Boolean {
        var white = 0
        var dark = 0
        for (i in from until to) {
            val v = lums[i]
            if (v > WHITE) white++ else if (v < BLACK) dark++
        }
        val need = (to - from) * EDGE_RATIO
        return white >= need || (black && dark >= need)
    }

    /**
     * 预检：缩小图最外面的四条行列都不是「边」时，detect 一定返回 null（第一行就是内容、最后一行也是……）。
     * 满版画面的页可以凭这四条细边直接判定「不裁」，省掉整页像素拷贝。
     * 参数是原图最外侧那一个块宽的四条细边（上 / 下 f 行、左 / 右 f 列），按同样的块平均计算，所以和 detect 的结论一致。
     */
    fun outerEdgesAreContent(
        top: () -> LumSource,
        bottom: () -> LumSource,
        left: () -> LumSource,
        right: () -> LumSource,
        black: Boolean,
    ): Boolean {
        val t = top()
        if (t.w < 8) return true // 太小的图 detect 也不裁
        var buf = IntArray(t.w)
        t.row(0, buf)
        if (isEdgeLine(buf, 0, t.w, black)) return false
        val b = bottom()
        b.row(0, buf)
        if (isEdgeLine(buf, 0, b.w, black)) return false
        val l = left()
        if (l.h < 8) return true
        buf = IntArray(l.h)
        l.col(0, buf, 0, l.h)
        if (isEdgeLine(buf, 0, l.h, black)) return false
        val r = right()
        r.col(0, buf, 0, r.h)
        return !isEdgeLine(buf, 0, r.h, black)
    }

    /** ARGB 像素的亮度（0~255） */
    fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    fun detect(px: IntArray, w: Int, h: Int, black: Boolean = false): Box? {
        if (w < 8 || h < 8 || px.size < w * h) return null
        return detect(ArgbLum(px, w, h), black)
    }

    /** 返回的 Box 是 src 的坐标（src.w × src.h） */
    fun detect(src: LumSource, black: Boolean = false): Box? {
        val w = src.w
        val h = src.h
        if (w < 8 || h < 8) return null
        // 一次取一整行（列）的亮度再计数：只有扫描到的边缘行列会被读取
        val rowBuf = IntArray(w)
        val colBuf = IntArray(h)
        fun rowEdge(y: Int): Boolean {
            src.row(y, rowBuf)
            return isEdgeLine(rowBuf, 0, w, black)
        }
        // 列只看上下白边之间的内容带：上下白边本来就是白的，跳过它们只会更保守（少裁、不会多裁），也少读很多像素
        var bandTop = 0
        var bandBottom = h
        fun colEdge(x: Int): Boolean {
            src.col(x, colBuf, bandTop, bandBottom)
            return isEdgeLine(colBuf, bandTop, bandBottom, black)
        }

        // 从上往下找第一行内容；一直找不到 = 整页都是边（全白 / 全黑）：不裁
        var firstContent = -1
        for (y in 0 until h) {
            if (!rowEdge(y)) {
                firstContent = y
                break
            }
        }
        if (firstContent < 0) return null

        val maxY = (h * MAX_SIDE).toInt()
        val maxX = (w * MAX_SIDE).toInt()
        val top = min(firstContent, maxY)
        var bottom = h
        while (h - bottom < maxY && rowEdge(bottom - 1)) bottom--
        bandTop = top
        bandBottom = bottom
        var left = 0
        while (left < maxX && colEdge(left)) left++
        var right = w
        while (w - right < maxX && colEdge(right - 1)) right--

        if (top == 0 && left == 0 && bottom == h && right == w) return null
        val px0 = max(1, (w * PAD).toInt())
        val py0 = max(1, (h * PAD).toInt())
        val b = Box(max(0, left - px0), max(0, top - py0), min(w, right + px0), min(h, bottom + py0))
        if (b.width <= 0 || b.height <= 0) return null
        if (b.width.toLong() * b.height < w.toLong() * h * MIN_AREA) return null
        // 加上安全边距后已经和原图一样大：不必裁
        if (b.left == 0 && b.top == 0 && b.right == w && b.bottom == h) return null
        return b
    }
}

/**
 * 双页排版：按页面宽高比把页码分组成「一屏显示的页」。
 *  - coverAlone：第一页（封面）单独显示
 *  - shift：「错开一页」，每一段连续竖图（封面之后、每张横图之后）的配对都后移一页，修正跨页没对齐
 *  - 宽图（宽 > 高，通常是跨页图）单独显示
 *  - 其余按顺序两两配对；剩下落单的一页单独显示
 * 每一组按阅读顺序排列（显示时由页序决定哪一页放左边）。
 */
object Spreads {
    /** 宽高比超过它就算横图（跨页）：双页模式里单独显示，拆分跨页时拆成两半 */
    const val WIDE = 1.0f

    fun build(aspects: FloatArray, coverAlone: Boolean, shift: Boolean = false): List<IntArray> {
        val out = ArrayList<IntArray>()
        val n = aspects.size
        var i = 0
        fun wide(k: Int) = aspects[k] > WIDE
        if (n > 0 && coverAlone) {
            out += intArrayOf(0)
            i = 1
        }
        // 「错开一页」对每一段连续竖图都生效：封面之后、每张横图之后各算一段，段首先单独放一页。
        // 这样不管读到哪一段，打开开关都会立刻改变当前的配对。
        var segmentStart = true
        while (i < n) {
            if (wide(i)) {
                out += intArrayOf(i)
                i++
                segmentStart = true
                continue
            }
            if (shift && segmentStart) {
                out += intArrayOf(i)
                i++
                segmentStart = false
                continue
            }
            segmentStart = false
            if (i + 1 >= n || wide(i + 1)) {
                out += intArrayOf(i)
                i++
            } else {
                out += intArrayOf(i, i + 1)
                i += 2
            }
        }
        return out
    }

    fun single(n: Int): List<IntArray> = List(n) { intArrayOf(it) }

    /** 包含第 page 页的那一组 */
    fun indexOf(units: List<IntArray>, page: Int): Int =
        units.indexOfFirst { page in it }.let { if (it < 0) 0 else it }
}

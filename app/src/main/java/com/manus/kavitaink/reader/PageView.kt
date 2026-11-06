package com.manus.kavitaink.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface PageSource : Closeable {
    val count: Int
    fun decode(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean): Bitmap?
}

private const val MAX_PIXELS = 8_000_000L

/** 已下载的漫画页图片 */
class ImageFolderSource(dir: File) : PageSource {
    private val files: List<File> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && !it.name.endsWith(".part") }
        .sortedBy { it.name }

    override val count: Int get() = files.size

    override fun decode(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean): Bitmap? {
        val f = files.getOrNull(index) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val scale = if (fitWidth) reqW.toFloat() / o.outWidth
        else min(reqW.toFloat() / o.outWidth, reqH.toFloat() / o.outHeight)
        var s = 1
        while (scale * s * 2 <= 1f) s *= 2
        while ((o.outWidth / s).toLong() * (o.outHeight / s) > MAX_PIXELS) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = s
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    override fun close() {}
}

/** 系统自带 PdfRenderer，无需第三方库 */
class PdfSource(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    override val count: Int get() = renderer.pageCount

    @Synchronized
    override fun decode(index: Int, reqW: Int, reqH: Int, fitWidth: Boolean): Bitmap? {
        if (index !in 0 until renderer.pageCount) return null
        val p = renderer.openPage(index)
        try {
            val scale = if (fitWidth) reqW.toFloat() / p.width
            else min(reqW.toFloat() / p.width, reqH.toFloat() / p.height)
            var w = max(1, (p.width * scale).toInt())
            var h = max(1, (p.height * scale).toInt())
            val px = w.toLong() * h
            if (px > MAX_PIXELS) {
                val f = sqrt(MAX_PIXELS.toDouble() / px)
                w = max(1, (w * f).toInt())
                h = max(1, (h * f).toInt())
            }
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return b
        } finally {
            p.close()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

/**
 * 单页显示视图。适应屏幕 / 适应宽度两种模式；适应宽度时长图按 92% 屏高分段翻页（无滚动动画）。
 * 使用软件图层，避免超长图超过 GPU 纹理尺寸上限而无法显示。
 */
class PageView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        private set
    var fitWidth = false
    var onSize: ((Int, Int) -> Unit)? = null
    var contrast = false
        set(v) {
            field = v
            paint.colorFilter = if (v) contrastFilter else null
            invalidate()
        }

    private var offsetY = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
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

    private fun scaleOf(b: Bitmap): Float =
        if (fitWidth) width.toFloat() / b.width
        else min(width.toFloat() / b.width, height.toFloat() / b.height)

    private fun scaledHeight(b: Bitmap) = b.height * scaleOf(b)

    fun setPage(b: Bitmap, fromEnd: Boolean) {
        bitmap = b
        offsetY = if (fitWidth && fromEnd) max(0f, scaledHeight(b) - height) else 0f
        invalidate()
    }

    fun stepForward(): Boolean {
        val b = bitmap ?: return false
        if (!fitWidth) return false
        val sh = scaledHeight(b)
        if (offsetY + height >= sh - 2) return false
        offsetY = min(offsetY + height * 0.92f, sh - height)
        invalidate()
        return true
    }

    fun stepBack(): Boolean {
        if (bitmap == null || !fitWidth || offsetY <= 1f) return false
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
        val b = bitmap ?: return
        if (b.isRecycled) return
        val s = scaleOf(b)
        val dw = b.width * s
        val dh = b.height * s
        val left = (width - dw) / 2f
        val top = if (fitWidth && dh > height) -offsetY else (height - dh) / 2f
        dst.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(b, null, dst, paint)
    }
}

package net.yunyuyuan.kavitaink.reader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.text.TextUtils
import android.text.TextPaint
import android.view.View
import net.yunyuyuan.kavitaink.Prefs
import net.yunyuyuan.kavitaink.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 阅读状态栏：一条固定高度的细条，左边章节名，右边「页码 · 进度 · 时间 · 电量」。
 * 只在翻页 / 打开章节 / 关闭菜单时由阅读器调用 [update]；内容没变就不重绘，不跑任何定时器。
 */
class StatusBarView(ctx: Context) : View(ctx) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
    }
    private val fill = Paint().apply { color = Color.BLACK }
    private val box = RectF()

    private var left = ""
    private var right = ""
    private var battery = -1

    /** 0 = 小，1 = 中 */
    var size: Int = 0
        set(v) {
            field = v
            paint.textSize = sp(if (v == 1) 13f else 11f)
            stroke.strokeWidth = max(1f, resources.displayMetrics.density)
            requestLayout()
            invalidate()
        }

    init {
        size = 0
        setBackgroundColor(Color.WHITE)
    }

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    /** 状态栏高度（px） */
    val barHeight: Int get() = (paint.textSize * 1.55f + dpf(4f)).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    fun update(left: String, right: String, battery: Int) {
        if (left == this.left && right == this.right && battery == this.battery) return
        this.left = left
        this.right = right
        this.battery = battery
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dpf(10f)
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
        var x = width - pad
        // 电量：画一个电池图标 + 百分比（不依赖 emoji 字体，老设备也能显示）
        if (battery >= 0) {
            val pct = "$battery%"
            val tw = paint.measureText(pct)
            canvas.drawText(pct, x - tw, baseline, paint)
            x -= tw + dpf(3f)
            val h = paint.textSize * 0.62f
            val w = h * 1.9f
            val nub = max(2f, h * 0.18f)
            val top = height / 2f - h / 2f
            box.set(x - w, top, x - nub, top + h)
            canvas.drawRect(box, stroke)
            canvas.drawRect(x - nub, top + h * 0.3f, x, top + h * 0.7f, fill)
            val inset = stroke.strokeWidth * 1.5f
            val inner = (box.width() - inset * 2) * battery.coerceIn(0, 100) / 100f
            if (inner > 0) canvas.drawRect(box.left + inset, box.top + inset, box.left + inset + inner, box.bottom - inset, fill)
            x -= w + dpf(8f)
        }
        if (right.isNotEmpty()) {
            val tw = paint.measureText(right)
            canvas.drawText(right, x - tw, baseline, paint)
            x -= tw + dpf(12f)
        }
        if (left.isNotEmpty()) {
            val avail = x - pad
            if (avail > paint.textSize * 2) {
                val s = TextUtils.ellipsize(left, paint, avail, TextUtils.TruncateAt.END)
                canvas.drawText(s, 0, s.length, pad, baseline, paint)
            }
        }
    }

    companion object {
        /** 当前电量百分比，取不到时返回 -1 */
        fun batteryLevel(ctx: Context): Int {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
            if (v in 1..100) return v
            // 部分设备不支持上面的接口：读取粘性广播（不注册接收器，不会常驻）
            val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level >= 0 && scale > 0) level * 100 / scale else -1
        }
    }
}

/** 状态栏设置对话框：阅读器菜单和设置页共用，每一项点了立即生效 */
object StatusBarDialog {
    fun show(ctx: Context, sb: Prefs.StatusBarPrefs, novel: Boolean, onChange: () -> Unit) {
        val bits = intArrayOf(Prefs.SB_PAGE, Prefs.SB_PERCENT, Prefs.SB_CHAPTER, Prefs.SB_TIME, Prefs.SB_BATTERY)
        val labels = arrayOf(
            ctx.getString(R.string.sb_show),
            ctx.getString(R.string.sb_item_page),
            ctx.getString(if (novel) R.string.sb_item_percent_book else R.string.sb_item_percent_chapter),
            ctx.getString(R.string.sb_item_chapter),
            ctx.getString(R.string.sb_item_time),
            ctx.getString(R.string.sb_item_battery),
            ctx.getString(R.string.sb_at_top),
            ctx.getString(R.string.sb_larger),
        )
        val items = sb.items
        val checked = BooleanArray(labels.size) { i ->
            when (i) {
                0 -> sb.on
                in 1..5 -> items and bits[i - 1] != 0
                6 -> sb.top
                else -> sb.size == 1
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (novel) R.string.sb_title_novel else R.string.sb_title_manga)
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                when (i) {
                    0 -> sb.on = on
                    in 1..5 -> sb.items = if (on) sb.items or bits[i - 1] else sb.items and bits[i - 1].inv()
                    6 -> sb.top = on
                    else -> sb.size = if (on) 1 else 0
                }
                onChange()
            }
            .setPositiveButton(R.string.done, null)
            .show()
    }
}

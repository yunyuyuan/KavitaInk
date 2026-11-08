package net.yunyuyuan.kavitaink.core

/**
 * 拆分跨页：竖屏时把宽页（宽 > 高，和双页模式判断横图的标准一致）拆成左右两半，一次显示一半。
 * 第 1 页（封面）不拆：宽封面通常是封底和封面连在一起，按阅读顺序切开会先看到封底。
 * 这里只有纯计算，不依赖 Android，便于单元测试。
 */
object Split {
    const val WHOLE = 0
    const val LEFT = 1
    const val RIGHT = 2

    /** 第 index 页是否需要拆 */
    fun splittable(index: Int, aspect: Float): Boolean = index > 0 && aspect > Spreads.WIDE

    /** 阅读顺序里的第 part 半（0 = 前半，1 = 后半）对应原图的哪一侧：右→左时先看右半 */
    fun side(part: Int, rtl: Boolean): Int = if ((part == 0) == rtl) RIGHT else LEFT

    /**
     * 原图里某一侧的区域 [left, top, right, bottom]（像素或点，和 w / h 同单位）；WHOLE 返回 null。
     * 从正中间切；宽度为奇数时多出的一列归右半。
     */
    fun half(w: Int, h: Int, side: Int): IntArray? = when (side) {
        LEFT -> intArrayOf(0, 0, w / 2, h)
        RIGHT -> intArrayOf(w / 2, 0, w, h)
        else -> null
    }

    /** 在某一侧上检测出的裁切比例（相对这一侧）→ 相对整张原图的比例；空数组（不需要裁）原样返回 */
    fun toFull(frac: FloatArray, base: IntArray, w: Int, h: Int): FloatArray {
        if (frac.size != 4) return frac
        val bw = (base[2] - base[0]).toFloat()
        val bh = (base[3] - base[1]).toFloat()
        return floatArrayOf(
            (base[0] + frac[0] * bw) / w,
            (base[1] + frac[1] * bh) / h,
            (base[0] + frac[2] * bw) / w,
            (base[1] + frac[3] * bh) / h,
        )
    }

    /** 阅读位置：「页码」，停在拆分页的后半时为「页码:1」（旧版本只存页码，照样能读） */
    fun encode(page: Int, part: Int): String = if (part > 0) "$page:1" else page.toString()

    /** encode 的逆运算：返回 (页码, 第几半) */
    fun decode(s: String): Pair<Int, Int> {
        val page = s.substringBefore(':').trim().toIntOrNull() ?: 0
        val part = s.substringAfter(':', "0").trim().toIntOrNull()?.coerceIn(0, 1) ?: 0
        return page to part
    }
}

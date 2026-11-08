package net.yunyuyuan.kavitaink.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4.0：拆分跨页的纯计算部分 */
class V140Test {

    @Test
    fun onlyWidePagesAfterCoverAreSplit() {
        assertFalse("封面不拆", Split.splittable(0, 1.45f))
        assertTrue(Split.splittable(5, 1.45f))
        assertFalse("正方形不算宽页（和双页模式一致）", Split.splittable(5, Spreads.WIDE))
        assertFalse(Split.splittable(5, 0.7f))
    }

    @Test
    fun halfOrderFollowsReadingDirection() {
        // 右→左：先右半后左半；左→右：先左半后右半
        assertEquals(Split.RIGHT, Split.side(0, rtl = true))
        assertEquals(Split.LEFT, Split.side(1, rtl = true))
        assertEquals(Split.LEFT, Split.side(0, rtl = false))
        assertEquals(Split.RIGHT, Split.side(1, rtl = false))
    }

    @Test
    fun halvesCoverTheWholeImageWithoutGap() {
        assertNull(Split.half(1600, 1150, Split.WHOLE))
        assertArrayEquals(intArrayOf(0, 0, 800, 1150), Split.half(1600, 1150, Split.LEFT))
        assertArrayEquals(intArrayOf(800, 0, 1600, 1150), Split.half(1600, 1150, Split.RIGHT))
        // 宽度为奇数：多出的一列归右半，两半正好接上
        val l = Split.half(1601, 900, Split.LEFT)!!
        val r = Split.half(1601, 900, Split.RIGHT)!!
        assertEquals(l[2], r[0])
        assertEquals(1601, (l[2] - l[0]) + (r[2] - r[0]))
    }

    @Test
    fun cropFractionsOnAHalfMapToTheWholeImage() {
        val eps = 1e-5f
        // 左半外侧（左边）有 25% 白边，上下各 10%
        val left = Split.toFull(floatArrayOf(0.25f, 0.1f, 1f, 0.9f), intArrayOf(0, 0, 800, 1150), 1600, 1150)
        assertArrayEquals(floatArrayOf(0.125f, 0.1f, 0.5f, 0.9f), left, eps)
        // 右半外侧（右边）有 25% 白边
        val right = Split.toFull(floatArrayOf(0f, 0.1f, 0.75f, 0.9f), intArrayOf(800, 0, 1600, 1150), 1600, 1150)
        assertArrayEquals(floatArrayOf(0.5f, 0.1f, 0.875f, 0.9f), right, eps)
        // 整页：原样
        val whole = Split.toFull(floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f), intArrayOf(0, 0, 1600, 1150), 1600, 1150)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f), whole, eps)
        // 不需要裁：空数组原样返回
        assertEquals(0, Split.toFull(FloatArray(0), intArrayOf(0, 0, 800, 1150), 1600, 1150).size)
    }

    @Test
    fun positionRoundTripAndLegacyValues() {
        assertEquals("12", Split.encode(12, 0))
        assertEquals("12:1", Split.encode(12, 1))
        assertEquals(12 to 1, Split.decode(Split.encode(12, 1)))
        assertEquals(12 to 0, Split.decode(Split.encode(12, 0)))
        // 旧版本只存页码
        assertEquals(37 to 0, Split.decode("37"))
        assertEquals(0 to 0, Split.decode(""))
        assertEquals(0 to 0, Split.decode("abc"))
        assertEquals(5 to 1, Split.decode("5:7"))
    }

    @Test
    fun spreadsKeepUsingTheSameWideThreshold() {
        // 双页模式里第 3 页（横图）单独一屏：和拆分跨页判断宽页的标准一致
        val units = Spreads.build(floatArrayOf(0.7f, 0.7f, 0.7f, 1.45f, 0.7f, 0.7f), coverAlone = true, shift = false)
        assertTrue(units.any { it.size == 1 && it[0] == 3 })
    }
}

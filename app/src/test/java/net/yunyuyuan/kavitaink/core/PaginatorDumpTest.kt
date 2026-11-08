package net.yunyuyuan.kavitaink.core

import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 把分页注入片段与脚本导出给浏览器端 / WebView 端验证脚本使用：
 *   PAGINATOR_DUMP=/path ./gradlew testDebugUnitTest --tests '*PaginatorDumpTest*'
 * 另设 PAGINATOR_COMBOS=/path/combos.json（数组，每项含 name 和 Style 字段）时，
 * 每个组合额外导出到 <PAGINATOR_DUMP>/<name>/script.js，供 tools/verify_layouts.py 逐个校验。
 */
class PaginatorDumpTest {
    @Test
    fun dump() {
        val out = System.getenv("PAGINATOR_DUMP").orEmpty()
        assumeTrue(out.isNotBlank())
        val dir = File(out).apply { mkdirs() }
        val style = Paginator.Style()
        write(dir, style)
        val combos = System.getenv("PAGINATOR_COMBOS").orEmpty()
        if (combos.isNotBlank()) {
            val arr = JSONArray(File(combos).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val my = o.optInt("marginY", style.marginTop)
                val s = Paginator.Style(
                    marginX = o.optInt("marginX", style.marginX),
                    marginTop = my,
                    marginBottom = my,
                    lineHeight = o.optInt("lineHeight", style.lineHeight),
                    forceBlack = o.optBoolean("forceBlack", style.forceBlack),
                    bookStyle = o.optBoolean("bookStyle", style.bookStyle),
                    textIndent = o.optInt("textIndent", style.textIndent),
                    paraSpacing = o.optInt("paraSpacing", style.paraSpacing),
                    justify = o.optBoolean("justify", style.justify),
                    letterSpacing = o.optInt("letterSpacing", style.letterSpacing),
                )
                write(File(dir, o.getString("name")).apply { mkdirs() }, s)
            }
        }
    }

    private fun write(dir: File, style: Paginator.Style) {
        File(dir, "head_template.txt").writeText(Paginator.headTemplate(style))
        File(dir, "style.json").writeText(
            "{\"mx\":${style.marginX},\"mt\":${style.marginTop},\"mb\":${style.marginBottom}}"
        )
        // 11111 / 22222 是尺寸提示占位，由验证脚本替换为实际视口尺寸
        File(dir, "script.js").writeText(Paginator.script(7, style, 11111, 22222))
    }
}

package com.manus.kavitaink.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 把分页注入片段与脚本导出给浏览器端验证脚本使用：PAGINATOR_DUMP=/path ./gradlew testDebugUnitTest */
class PaginatorDumpTest {
    @Test
    fun dump() {
        val out = System.getenv("PAGINATOR_DUMP").orEmpty()
        assumeTrue(out.isNotBlank())
        val dir = File(out).apply { mkdirs() }
        val style = Paginator.Style()
        File(dir, "head_template.txt").writeText(Paginator.headTemplate(style))
        File(dir, "style.json").writeText(
            "{\"mx\":${style.marginX},\"mt\":${style.marginTop},\"mb\":${style.marginBottom}}"
        )
        // 11111 / 22222 是尺寸提示占位，由验证脚本替换为实际视口尺寸
        File(dir, "script.js").writeText(Paginator.script(7, style, 11111, 22222))
    }
}

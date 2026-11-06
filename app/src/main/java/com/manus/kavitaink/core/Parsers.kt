package com.manus.kavitaink.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 /api/Series/volumes 的返回值，把“卷 → 章节”结构拍平成可下载列表并生成中文标题。
 *
 * Kavita 约定（已在 v0.9.1.4 实测）：
 *  - 卷 minNumber = -100000：无卷的散章（loose leaf）
 *  - 卷 minNumber = 100000：特别篇卷
 *  - 章节 range = "-100000"：默认章节，即“整卷就是一个文件”
 *  - 图书库中的单本 EPUB/PDF 通常被标记为 isSpecial
 */
object Parsers {
    const val LOOSE = -100000.0
    const val SPECIAL = 100000.0
    const val DEFAULT_RANGE = "-100000"

    fun chapters(json: String, seriesId: Int, seriesFormat: Int): List<ChapterItem> {
        val bookish = seriesFormat == Fmt.EPUB || seriesFormat == Fmt.PDF
        val arr = JSONArray(json)
        val out = ArrayList<ChapterItem>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val vMin = v.optDouble("minNumber", 0.0)
            val vName = v.s("name")
            val vGroup = when {
                vMin <= LOOSE + 0.5 -> 1
                vMin >= SPECIAL - 0.5 -> 2
                else -> 0
            }
            val chs = v.optJSONArray("chapters") ?: continue
            for (j in 0 until chs.length()) {
                val c = chs.optJSONObject(j) ?: continue
                val special = c.optBoolean("isSpecial")
                val group = if (special) 2 else vGroup
                val files = c.optJSONArray("files")
                var bytes = 0L
                var ext = ""
                var fileFormat = -1
                if (files != null) {
                    for (k in 0 until files.length()) {
                        val f = files.optJSONObject(k) ?: continue
                        bytes += f.optLong("bytes")
                        if (ext.isEmpty()) ext = f.s("extension")
                        if (fileFormat < 0) fileFormat = f.optInt("format", -1)
                    }
                }
                val format = when {
                    c.has("format") && !c.isNull("format") -> c.optInt("format", seriesFormat)
                    fileFormat >= 0 -> fileFormat
                    else -> seriesFormat
                }
                out += ChapterItem(
                    id = c.optInt("id"),
                    seriesId = seriesId,
                    volumeId = v.optInt("id"),
                    label = label(group, vName, c, bookish || format == Fmt.EPUB || format == Fmt.PDF),
                    group = group,
                    volNum = if (group == 0) vMin else 0.0,
                    sortOrder = c.optDouble("sortOrder", 0.0),
                    pages = c.optInt("pages"),
                    format = format,
                    ext = ext,
                    bytes = bytes,
                    fileCount = files?.length() ?: 1,
                )
            }
        }
        return out.sortedWith(compareBy<ChapterItem>({ it.group }, { it.volNum }, { it.sortOrder }, { it.id }))
    }

    private fun label(group: Int, volName: String, c: JSONObject, bookish: Boolean): String {
        val range = c.s("range")
        val titleName = c.s("titleName").trim()
        val title = c.s("title").trim()
        val unit = if (bookish) "章" else "话"
        return when (group) {
            2 -> listOf(title, titleName, range)
                .firstOrNull { it.isNotBlank() && it != DEFAULT_RANGE } ?: "特别篇"
            1 -> if (range.isBlank() || range == DEFAULT_RANGE) {
                listOf(title, titleName).firstOrNull { it.isNotBlank() && it != DEFAULT_RANGE } ?: "章节"
            } else {
                chapterLabel(range, unit) + suffix(titleName, range)
            }
            else -> {
                val vol = volumeLabel(volName)
                if (range.isBlank() || range == DEFAULT_RANGE) vol + suffix(titleName, range)
                else vol + " " + chapterLabel(range, unit) + suffix(titleName, range)
            }
        }
    }

    private fun suffix(titleName: String, range: String): String =
        if (titleName.isBlank() || titleName == range || titleName == DEFAULT_RANGE) "" else " · $titleName"

    private fun isNumber(s: String) = s.isNotBlank() && s.all { it.isDigit() || it == '.' || it == '-' }

    fun volumeLabel(name: String): String = when {
        name.isBlank() -> "单卷"
        isNumber(name) -> "第${name}卷"
        else -> name
    }

    private fun chapterLabel(range: String, unit: String): String =
        if (isNumber(range)) "第$range$unit" else range
}

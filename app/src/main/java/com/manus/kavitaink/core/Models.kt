package com.manus.kavitaink.core

import org.json.JSONObject

/** Kavita 的 MangaFormat 枚举 */
object Fmt {
    const val IMAGE = 0
    const val ARCHIVE = 1
    const val UNKNOWN = 2
    const val EPUB = 3
    const val PDF = 4

    fun label(f: Int): String = when (f) {
        IMAGE -> "图片"
        ARCHIVE -> "压缩包"
        EPUB -> "EPUB"
        PDF -> "PDF"
        else -> "未知"
    }
}

/** 本地离线存储形式 */
enum class Kind { IMAGES, EPUB, PDF }

fun kindOf(format: Int, ext: String): Kind = when {
    format == Fmt.EPUB || ext.equals(".epub", true) -> Kind.EPUB
    format == Fmt.PDF || ext.equals(".pdf", true) -> Kind.PDF
    else -> Kind.IMAGES
}

data class Library(val id: Int, val name: String, val type: Int) {
    val typeLabel: String
        get() = when (type) {
            0 -> "漫画"
            1 -> "美漫"
            2 -> "图书"
            3 -> "图片"
            4 -> "轻小说"
            5 -> "ComicVine"
            else -> "其他"
        }
}

data class SeriesItem(
    val id: Int,
    val name: String,
    val libraryId: Int,
    val libraryName: String,
    val format: Int,
    val pages: Int,
)

data class SeriesPage(
    val items: List<SeriesItem>,
    val page: Int,
    val totalPages: Int,
    val totalItems: Int,
)

/**
 * 一个可下载、可阅读的单元（对应 Kavita 的 Chapter）。
 * group: 0 = 普通卷, 1 = 无卷章节, 2 = 特别篇/单本
 */
data class ChapterItem(
    val id: Int,
    val seriesId: Int,
    val volumeId: Int,
    val label: String,
    val group: Int,
    val volNum: Double,
    val sortOrder: Double,
    val pages: Int,
    val format: Int,
    val ext: String,
    val bytes: Long,
    val fileCount: Int,
) {
    val kind: Kind get() = kindOf(format, ext)
}

/** 下载任务；下载完成后同样作为 chapter.json 的元数据写入本地 */
data class DownloadTask(
    val serverKey: String,
    val seriesId: Int,
    val seriesName: String,
    val seriesFormat: Int,
    val chapterId: Int,
    val label: String,
    val group: Int,
    val volNum: Double,
    val sortOrder: Double,
    val pages: Int,
    val format: Int,
    val ext: String,
    val bytes: Long,
    val fileCount: Int,
) {
    val kind: Kind get() = kindOf(format, ext)

    fun toJson(): JSONObject = JSONObject()
        .put("serverKey", serverKey)
        .put("seriesId", seriesId)
        .put("seriesName", seriesName)
        .put("seriesFormat", seriesFormat)
        .put("chapterId", chapterId)
        .put("label", label)
        .put("group", group)
        .put("volNum", volNum)
        .put("sortOrder", sortOrder)
        .put("pages", pages)
        .put("format", format)
        .put("ext", ext)
        .put("bytes", bytes)
        .put("fileCount", fileCount)

    companion object {
        fun fromJson(o: JSONObject) = DownloadTask(
            serverKey = o.s("serverKey"),
            seriesId = o.optInt("seriesId"),
            seriesName = o.s("seriesName"),
            seriesFormat = o.optInt("seriesFormat"),
            chapterId = o.optInt("chapterId"),
            label = o.s("label"),
            group = o.optInt("group"),
            volNum = o.optDouble("volNum", 0.0),
            sortOrder = o.optDouble("sortOrder", 0.0),
            pages = o.optInt("pages"),
            format = o.optInt("format"),
            ext = o.s("ext"),
            bytes = o.optLong("bytes"),
            fileCount = o.optInt("fileCount", 1),
        )

        fun of(serverKey: String, seriesName: String, seriesFormat: Int, c: ChapterItem) = DownloadTask(
            serverKey = serverKey,
            seriesId = c.seriesId,
            seriesName = seriesName,
            seriesFormat = seriesFormat,
            chapterId = c.id,
            label = c.label,
            group = c.group,
            volNum = c.volNum,
            sortOrder = c.sortOrder,
            pages = c.pages,
            format = c.format,
            ext = c.ext,
            bytes = c.bytes,
            fileCount = c.fileCount,
        )
    }
}

/** JSON 里的 null 在 Android 的 org.json 中 optString 会返回 "null"，这里统一转成空串 */
fun JSONObject.s(name: String): String =
    if (!has(name) || isNull(name)) "" else optString(name, "")

fun formatBytes(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}

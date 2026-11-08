package net.yunyuyuan.kavitaink.core

import net.yunyuyuan.kavitaink.R
import org.json.JSONObject

/** Kavita 的 MangaFormat 枚举 */
object Fmt {
    const val IMAGE = 0
    const val ARCHIVE = 1
    const val UNKNOWN = 2
    const val EPUB = 3
    const val PDF = 4

    fun label(f: Int, x: Texts): String = when (f) {
        IMAGE -> x.s(R.string.fmt_image)
        ARCHIVE -> x.s(R.string.fmt_archive)
        EPUB -> "EPUB"
        PDF -> "PDF"
        else -> x.s(R.string.fmt_unknown)
    }
}

/** 本地离线存储形式 */
/**
 * 本地保存方式：
 *  EPUB / PDF / ZIP（cbz、zip）= 原文件，保存在公共下载目录，保持服务器上的目录结构
 *  IMAGES = 逐页图片，保存在应用私有目录（cbr / cb7 等本机读不了的格式、多文件章节、本机无法渲染的 PDF）
 */
enum class Kind { IMAGES, EPUB, PDF, ZIP }

fun kindOf(format: Int, ext: String): Kind = when {
    format == Fmt.EPUB || ext.equals(".epub", true) -> Kind.EPUB
    format == Fmt.PDF || ext.equals(".pdf", true) -> Kind.PDF
    ext.equals(".cbz", true) || ext.equals(".zip", true) -> Kind.ZIP
    else -> Kind.IMAGES
}

data class Library(val id: Int, val name: String, val type: Int, val folders: List<String> = emptyList()) {
    fun typeLabel(x: Texts): String = when (type) {
        0 -> x.s(R.string.lib_type_manga)
        1 -> x.s(R.string.lib_type_comic)
        2 -> x.s(R.string.lib_type_book)
        3 -> x.s(R.string.lib_type_image)
        4 -> x.s(R.string.lib_type_light_novel)
        5 -> "ComicVine"
        else -> x.s(R.string.lib_type_other)
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
    val src: LabelSrc,
    val group: Int,
    val volNum: Double,
    val sortOrder: Double,
    val pages: Int,
    val format: Int,
    val ext: String,
    val bytes: Long,
    val fileCount: Int,
    /** 服务器上的完整路径（第一个文件），用于生成本地目录结构 */
    val filePath: String = "",
) {
    val kind: Kind get() = kindOf(format, ext)

    fun title(x: Texts): String = Labels.of(group, src, x)
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
    /** 生成标题的原始信息；1.1.0 下载的旧数据没有，只有中文的 [label] */
    val src: LabelSrc? = null,
    /** 服务器上的完整路径（第一个文件） */
    val filePath: String = "",
) {
    val kind: Kind get() = kindOf(format, ext)

    /** 当前语言的章节标题 */
    fun title(x: Texts): String = Labels.render(group, src, label, x)

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
        .put("filePath", filePath)
        .apply { src?.let { put("labelSrc", it.toJson()) } }

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
            src = LabelSrc.fromJson(o.optJSONObject("labelSrc")),
            filePath = o.s("filePath"),
        )

        fun of(serverKey: String, seriesName: String, seriesFormat: Int, c: ChapterItem) = DownloadTask(
            serverKey = serverKey,
            seriesId = c.seriesId,
            seriesName = seriesName,
            seriesFormat = seriesFormat,
            chapterId = c.id,
            label = "",
            group = c.group,
            volNum = c.volNum,
            sortOrder = c.sortOrder,
            pages = c.pages,
            format = c.format,
            ext = c.ext,
            bytes = c.bytes,
            fileCount = c.fileCount,
            src = c.src,
            filePath = c.filePath,
        )
    }
}

/** JSON 里的 null 在 Android 的 org.json 中 optString 会返回 "null"，这里统一转成空串 */
fun JSONObject.s(name: String): String =
    if (!has(name) || isNull(name)) "" else optString(name, "")

/** 数字按当前语言的习惯格式化（如德语 1,5 MB），单位不翻译 */
fun formatBytes(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.getDefault(), "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}

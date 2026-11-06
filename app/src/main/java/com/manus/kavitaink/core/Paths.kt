package com.manus.kavitaink.core

/**
 * 把 Kavita 服务器上的文件路径映射成下载目录里的相对路径：
 *
 *   <书库名>/<文件相对书库根目录的路径>
 *
 * 例：书库「漫画」根目录 /data/manga，文件 /data/manga/One Piece/One Piece v01.cbz
 *   → 漫画/One Piece/One Piece v01.cbz
 *
 * 拿不到书库根目录（或文件不在任何根目录下）时，退回 <书库名>/<父文件夹名>/<文件名>。
 * 纯 Kotlin，可在 JVM 上测试。
 */
object Paths {

    /** FAT / exFAT / Android 存储都不允许的字符 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")

    /** 单段路径的最大字节数（多数文件系统上限 255，留一点给 .part 临时文件的前后缀） */
    const val MAX_SEGMENT_BYTES = 240

    /** 统一分隔符、去掉多余的斜杠；Windows 服务器的 C:\Manga\x.cbz 也能处理 */
    fun normalize(p: String): String {
        var s = p.trim().replace('\\', '/')
        while (s.contains("//")) s = s.replace("//", "/")
        return s.trimEnd('/')
    }

    private fun isWindows(p: String) = p.length >= 2 && p[1] == ':' && p[0].isLetter()

    /**
     * 返回文件相对书库根目录的各段路径；匹配不到任何根目录时返回 null。
     * 有多个根目录时取最长的匹配（嵌套目录的情况）。
     */
    fun relativeSegments(filePath: String, folders: List<String>): List<String>? {
        val f = normalize(filePath)
        if (f.isEmpty()) return null
        var best: String? = null
        for (raw in folders) {
            val root = normalize(raw)
            if (root.isEmpty()) continue
            val ignoreCase = isWindows(root) || isWindows(f)
            val prefix = "$root/"
            if (f.startsWith(prefix, ignoreCase) && (best == null || root.length > best.length)) best = root
        }
        val root = best ?: return null
        val rest = f.substring(root.length + 1).split('/').filter { it.isNotEmpty() }
        return rest.ifEmpty { null }
    }

    /** 退回方案：父文件夹名 + 文件名 */
    fun fallbackSegments(filePath: String): List<String> {
        val parts = normalize(filePath).split('/').filter { it.isNotEmpty() && !(it.length == 2 && it[1] == ':') }
        return parts.takeLast(2)
    }

    /**
     * 下载目录里的相对路径（用 / 分隔，每段都已清理）。
     * fileName 用于 filePath 为空时（理论上不会发生）生成文件名。
     */
    fun contentPath(libraryName: String, filePath: String, folders: List<String>, fileName: String): String {
        val segs = relativeSegments(filePath, folders)
            ?: fallbackSegments(filePath).ifEmpty { listOf(fileName) }
        val lib = sanitize(libraryName.ifBlank { "Kavita" })
        return (listOf(lib) + segs.map(::sanitize)).joinToString("/")
    }

    /** 清理一段路径：替换非法字符，去掉首尾空格和结尾的点，限制长度（保留扩展名） */
    fun sanitize(seg: String): String {
        var s = seg.replace(ILLEGAL, "_").trim()
        s = s.trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") s = "_"
        if (s.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES) return s
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
        var stem = s.substring(0, s.length - ext.length)
        val budget = MAX_SEGMENT_BYTES - ext.toByteArray(Charsets.UTF_8).size
        while (stem.isNotEmpty() && stem.toByteArray(Charsets.UTF_8).size > budget) {
            // 按码点截断，避免截出半个汉字或半个表情
            val cp = stem.codePointBefore(stem.length)
            stem = stem.substring(0, stem.length - Character.charCount(cp))
        }
        return stem.trimEnd('.', ' ').ifEmpty { "_" } + ext
    }

    /** 下载中的临时文件：同目录下的隐藏文件 .<文件名>.part，其他阅读器默认不会显示 */
    fun partName(fileName: String) = ".$fileName.part"
}

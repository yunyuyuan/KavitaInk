package com.manus.kavitaink.core

import org.json.JSONObject
import java.io.File

data class LocalChapter(
    val meta: DownloadTask,
    val kind: Kind,
    /** 私有索引目录（chapter.json；逐页下载时还有 pages/） */
    val dir: File,
    val downloadedAt: Long,
    /** 原文件（EPUB / PDF / ZIP）在下载目录里的位置；逐页下载的为 null */
    val file: File?,
) {
    val serverKey get() = meta.serverKey
    val seriesId get() = meta.seriesId
    val chapterId get() = meta.chapterId
    fun title(x: Texts): String = meta.title(x)
    val epubFile get() = file ?: File(dir, "missing.epub")
    val pdfFile get() = file ?: File(dir, "missing.pdf")
    val zipFile get() = file ?: File(dir, "missing.cbz")
    val pagesDir get() = File(dir, Storage.PAGES_DIR)
}

data class LocalSeries(
    val serverKey: String,
    val seriesId: Int,
    val name: String,
    val format: Int,
    val dir: File,
    val chapters: List<LocalChapter>,
) {
    val cover get() = File(dir, Storage.COVER)
}

/**
 * 两个目录：
 *
 * 1. 下载目录 [contentRoot]（公共存储，其他阅读器也能打开），只放原文件，保持服务器上的结构：
 *      <contentRoot>/<书库名>/<相对书库根目录的原路径>
 *
 * 2. 私有索引 [root]（应用自己的目录），记录哪些章节已下载、对应哪个文件：
 *      <root>/<serverKey>/s<seriesId>/series.json、cover
 *      <root>/<serverKey>/s<seriesId>/c<chapterId>/chapter.json   ← 下载完成才写入，path 指向原文件
 *      <root>/<serverKey>/s<seriesId>/c<chapterId>/target         ← 下载中：目标文件的相对路径
 *      <root>/<serverKey>/s<seriesId>/c<chapterId>/pages/00000.jpg ← 只有逐页下载才有
 *
 * 原文件被其他应用删除或移走后，对应章节自动视为未下载。
 */
class Storage(val root: File, val contentRoot: File) {

    companion object {
        const val CHAPTER_META = "chapter.json"
        const val SERIES_META = "series.json"
        const val TARGET = "target"
        const val COVER = "cover"
        const val PAGES_DIR = "pages"

        val chapterOrder = compareBy<LocalChapter>(
            { it.meta.group }, { it.meta.volNum }, { it.meta.sortOrder }, { it.meta.chapterId }
        )

        fun writeAtomic(f: File, text: String) {
            f.parentFile?.mkdirs()
            // 并发下载同一系列的几章时可能同时写同一个文件：临时文件按线程区分
            val tmp = File(f.parentFile, f.name + ".tmp" + Thread.currentThread().id)
            tmp.writeText(text)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    fun seriesDir(serverKey: String, seriesId: Int) = File(File(root, serverKey), "s$seriesId")

    fun chapterDir(serverKey: String, seriesId: Int, chapterId: Int) =
        File(seriesDir(serverKey, seriesId), "c$chapterId")

    /** 下载目录里的文件；rel 用 / 分隔 */
    fun contentFile(rel: String) = File(contentRoot, rel)

    /** 下载中的临时文件（同目录的隐藏文件） */
    fun partFile(dest: File) = File(dest.parentFile, Paths.partName(dest.name))

    fun isComplete(serverKey: String, seriesId: Int, chapterId: Int) =
        readChapter(chapterDir(serverKey, seriesId, chapterId)) != null

    fun ensureSeries(t: DownloadTask) {
        val d = seriesDir(t.serverKey, t.seriesId)
        d.mkdirs()
        val f = File(d, SERIES_META)
        if (!f.exists()) {
            writeAtomic(
                f, JSONObject()
                    .put("serverKey", t.serverKey)
                    .put("seriesId", t.seriesId)
                    .put("name", t.seriesName)
                    .put("format", t.seriesFormat)
                    .toString()
            )
        }
    }

    /** 记录下载目标（开始下载原文件前调用），取消时据此删除临时文件 */
    fun writeTarget(dir: File, rel: String) = writeAtomic(File(dir, TARGET), rel)

    fun readTarget(dir: File): String? = File(dir, TARGET).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    /** path 为空表示逐页下载（内容在 dir/pages） */
    fun writeChapterMeta(dir: File, t: DownloadTask, kind: Kind, path: String) {
        val o = t.toJson().put("kind", kind.name).put("path", path).put("downloadedAt", System.currentTimeMillis())
        writeAtomic(File(dir, CHAPTER_META), o.toString())
        File(dir, TARGET).delete()
    }

    /** 读取已下载章节；原文件不在了（被其他应用删除 / 移走）返回 null */
    fun readChapter(dir: File): LocalChapter? {
        val f = File(dir, CHAPTER_META)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val kind = runCatching { Kind.valueOf(o.s("kind")) }.getOrDefault(Kind.IMAGES)
            val path = o.s("path")
            val file = if (kind == Kind.IMAGES || path.isEmpty()) null else contentFile(path)
            if (kind != Kind.IMAGES && (file == null || !file.isFile)) return null
            LocalChapter(DownloadTask.fromJson(o), kind, dir, o.optLong("downloadedAt"), file)
        }.getOrNull()
    }

    fun listChapters(serverKey: String, seriesId: Int): List<LocalChapter> =
        listChapters(seriesDir(serverKey, seriesId))

    fun listChapters(seriesDir: File): List<LocalChapter> =
        (seriesDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("c") }
            .mapNotNull(::readChapter)
            .sortedWith(chapterOrder)

    fun readSeries(dir: File): LocalSeries? {
        val f = File(dir, SERIES_META)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            LocalSeries(o.s("serverKey"), o.optInt("seriesId"), o.s("name"), o.optInt("format"), dir, listChapters(dir))
        }.getOrNull()
    }

    fun listShelf(): List<LocalSeries> {
        val out = ArrayList<LocalSeries>()
        for (server in root.listFiles() ?: emptyArray()) {
            if (!server.isDirectory) continue
            for (s in server.listFiles() ?: emptyArray()) {
                if (!s.isDirectory || !s.name.startsWith("s")) continue
                val series = readSeries(s) ?: continue
                if (series.chapters.isNotEmpty()) out += series
            }
        }
        return out
    }

    fun deleteChapter(c: LocalChapter) {
        c.file?.let { deleteContent(it) }
        c.dir.deleteRecursively()
        cleanupSeries(c.dir.parentFile)
    }

    /** 取消下载时清理未完成的文件（已完成的不动） */
    fun deletePartial(serverKey: String, seriesId: Int, chapterId: Int) {
        val d = chapterDir(serverKey, seriesId, chapterId)
        if (File(d, CHAPTER_META).exists()) return
        readTarget(d)?.let { rel ->
            val dest = contentFile(rel)
            val part = partFile(dest)
            part.delete()
            pruneEmptyDirs(dest.parentFile)
        }
        d.deleteRecursively()
        cleanupSeries(d.parentFile)
    }

    fun deleteSeries(s: LocalSeries) {
        // 只删本应用下载的文件；同一文件夹里用户自己放的其他文件保留
        s.chapters.forEach { c -> c.file?.let { deleteContent(it) } }
        s.dir.deleteRecursively()
    }

    private fun deleteContent(f: File) {
        f.delete()
        partFile(f).delete()
        pruneEmptyDirs(f.parentFile)
    }

    /** 从 dir 往上删除空文件夹，直到下载目录本身（不含） */
    private fun pruneEmptyDirs(start: File?) {
        val top = contentRoot.absoluteFile
        var d = start?.absoluteFile
        while (d != null && d != top && d.path.startsWith(top.path + File.separator)) {
            val children = d.list() ?: break
            if (children.isNotEmpty()) break
            if (!d.delete()) break
            d = d.parentFile
        }
    }

    private fun cleanupSeries(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        val hasChapters = (dir.listFiles() ?: emptyArray()).any { it.isDirectory && it.name.startsWith("c") }
        if (!hasChapters) dir.deleteRecursively()
    }

    /** 全部占用：私有索引（含逐页下载的图片、封面）+ 下载目录 */
    fun usedBytes(): Long = treeSize(root) + treeSize(contentRoot)

    fun sizeOf(c: LocalChapter): Long = treeSize(c.dir) + (c.file?.length() ?: 0L)

    fun sizeOf(s: LocalSeries): Long = treeSize(s.dir) + s.chapters.sumOf { it.file?.length() ?: 0L }

    private fun treeSize(dir: File): Long =
        if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
}

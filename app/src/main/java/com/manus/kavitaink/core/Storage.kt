package com.manus.kavitaink.core

import org.json.JSONObject
import java.io.File

data class LocalChapter(
    val meta: DownloadTask,
    val kind: Kind,
    val dir: File,
    val downloadedAt: Long,
) {
    val serverKey get() = meta.serverKey
    val seriesId get() = meta.seriesId
    val chapterId get() = meta.chapterId
    val label get() = meta.label
    val epubFile get() = File(dir, Storage.EPUB_FILE)
    val pdfFile get() = File(dir, Storage.PDF_FILE)
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
 * 离线目录结构（自描述，不依赖数据库）：
 *   <root>/<serverKey>/s<seriesId>/series.json
 *   <root>/<serverKey>/s<seriesId>/cover
 *   <root>/<serverKey>/s<seriesId>/c<chapterId>/chapter.json   ← 只有下载完成才写入
 *   <root>/<serverKey>/s<seriesId>/c<chapterId>/pages/00000.jpg | book.epub | book.pdf
 */
class Storage(val root: File) {

    companion object {
        const val CHAPTER_META = "chapter.json"
        const val SERIES_META = "series.json"
        const val COVER = "cover"
        const val EPUB_FILE = "book.epub"
        const val PDF_FILE = "book.pdf"
        const val PAGES_DIR = "pages"

        val chapterOrder = compareBy<LocalChapter>(
            { it.meta.group }, { it.meta.volNum }, { it.meta.sortOrder }, { it.meta.chapterId }
        )

        fun writeAtomic(f: File, text: String) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
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

    fun isComplete(serverKey: String, seriesId: Int, chapterId: Int) =
        File(chapterDir(serverKey, seriesId, chapterId), CHAPTER_META).isFile

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

    fun writeChapterMeta(dir: File, t: DownloadTask, kind: Kind) {
        val o = t.toJson().put("kind", kind.name).put("downloadedAt", System.currentTimeMillis())
        writeAtomic(File(dir, CHAPTER_META), o.toString())
    }

    fun readChapter(dir: File): LocalChapter? {
        val f = File(dir, CHAPTER_META)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val kind = runCatching { Kind.valueOf(o.s("kind")) }.getOrDefault(Kind.IMAGES)
            LocalChapter(DownloadTask.fromJson(o), kind, dir, o.optLong("downloadedAt"))
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
        c.dir.deleteRecursively()
        cleanupSeries(c.dir.parentFile)
    }

    /** 取消下载时清理未完成的目录（已完成的不动） */
    fun deletePartial(serverKey: String, seriesId: Int, chapterId: Int) {
        val d = chapterDir(serverKey, seriesId, chapterId)
        if (!File(d, CHAPTER_META).exists()) {
            d.deleteRecursively()
            cleanupSeries(d.parentFile)
        }
    }

    fun deleteSeries(s: LocalSeries) {
        s.dir.deleteRecursively()
    }

    private fun cleanupSeries(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        val hasChapters = (dir.listFiles() ?: emptyArray()).any { it.isDirectory && it.name.startsWith("c") }
        if (!hasChapters) dir.deleteRecursively()
    }

    fun usedBytes(): Long = if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
}

package com.manus.kavitaink.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.zip.ZipFile

/** 本地存储问题（空间不足、无法写入），重试没有意义 */
class StorageException(message: String) : IOException(message)

/**
 * 下载单个章节到本地。纯 Kotlin，不依赖 Android，可在 JVM 上直接测试。
 *
 *  - EPUB / PDF：GET /api/Download/chapter 下载原文件，支持 Range 断点续传（未完成部分保存在 *.part）
 *  - 图片类（cbz/cbr/cb7/zip/rar/图片文件夹）：逐页 GET /api/Reader/image，
 *    由服务器负责解压，客户端无需内置 rar/7z 解码，已下载的页会自动跳过
 *  - PDF 若本机无法渲染（pdfOk 返回 false），自动改为下载服务器渲染的页面图片
 *
 * 暂停 / 取消：调用方让 stopped() 返回 true 并调用 [abort]。正在进行的连接会被立即断开，
 * 已经写入磁盘的数据全部保留，下次从断点继续；是否删除由调用方决定（取消才删）。
 */
class Downloader(private val api: KavitaApi, private val storage: Storage) {

    /** 因暂停或取消而中止（不是错误） */
    class Cancelled : Exception("已停止")

    fun interface Progress {
        /** pages = true 时 done/total 是页数，否则是字节数 */
        fun update(done: Long, total: Long, pages: Boolean)
    }

    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var aborted = false

    /** 可在任意线程调用：断开当前连接，让阻塞中的读取立即返回 */
    fun abort() {
        aborted = true
        conn?.let { runCatching { it.disconnect() } }
    }

    fun download(
        t: DownloadTask,
        progress: Progress,
        stopped: () -> Boolean,
        pdfOk: (File) -> Boolean = { true },
    ): Kind {
        val cancelled = { aborted || stopped() }
        try {
            return doDownload(t, progress, cancelled, pdfOk)
        } catch (e: IOException) {
            // 连接被 abort() 断开时，各处可能抛出各种 IO 异常（Socket closed 等），统一视为停止
            if (cancelled()) throw Cancelled()
            throw e
        } finally {
            conn = null
        }
    }

    private fun doDownload(
        t: DownloadTask,
        progress: Progress,
        cancelled: () -> Boolean,
        pdfOk: (File) -> Boolean,
    ): Kind {
        if (t.serverKey != api.serverKey) throw ApiException(0, "该任务属于另一个服务器，请重新登录原服务器")
        val dir = storage.chapterDir(t.serverKey, t.seriesId, t.chapterId)
        dir.mkdirs()
        if (!dir.isDirectory) throw StorageException("无法创建下载目录，请检查存储空间")
        storage.ensureSeries(t)
        if (cancelled()) throw Cancelled()
        saveCover(t)

        var kind = t.kind
        when (kind) {
            Kind.EPUB -> {
                if (t.fileCount > 1) throw ApiException(0, "暂不支持包含多个文件的 EPUB 章节")
                val f = File(dir, Storage.EPUB_FILE)
                fetchFile(api.downloadUrl(t.chapterId), f, t.bytes, progress, cancelled)
                validateZip(f)
            }
            Kind.PDF -> {
                val f = File(dir, Storage.PDF_FILE)
                if (t.fileCount > 1) {
                    kind = Kind.IMAGES
                    fetchPages(t, dir, true, progress, cancelled)
                } else if (hasPages(dir) && !f.exists() && !File(f.path + ".part").exists()) {
                    // 上次已经改为按页下载（本机无法渲染该 PDF），继续按页
                    kind = Kind.IMAGES
                    fetchPages(t, dir, true, progress, cancelled)
                } else {
                    fetchFile(api.downloadUrl(t.chapterId), f, t.bytes, progress, cancelled)
                    if (!pdfOk(f)) {
                        f.delete()
                        kind = Kind.IMAGES
                        fetchPages(t, dir, true, progress, cancelled)
                    }
                }
            }
            Kind.IMAGES -> fetchPages(t, dir, t.format == Fmt.PDF, progress, cancelled)
        }
        if (cancelled()) throw Cancelled()
        storage.writeChapterMeta(dir, t, kind)
        return kind
    }

    private fun hasPages(dir: File): Boolean =
        (File(dir, Storage.PAGES_DIR).list() ?: emptyArray()).any { !it.endsWith(".part") }

    private fun saveCover(t: DownloadTask) {
        val cover = File(storage.seriesDir(t.serverKey, t.seriesId), Storage.COVER)
        if (cover.exists() && cover.length() > 0) return
        runCatching {
            val bytes = api.coverBytes(t.seriesId)
            if (bytes.isNotEmpty()) {
                val tmp = File(cover.parentFile, "cover.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(cover)
            }
        }
    }

    private fun open(url: String, rangeFrom: Long, accept: String): HttpURLConnection {
        val c = try {
            api.connect("GET", url, rangeFrom = rangeFrom, accept = accept, readTimeoutMs = 60_000, onOpen = { conn = it })
        } catch (e: IOException) {
            conn = null
            if (aborted) throw Cancelled()
            throw e
        }
        if (aborted) {
            runCatching { c.disconnect() }
            throw Cancelled()
        }
        return c
    }

    private fun fetchFile(url: String, dest: File, expected: Long, progress: Progress, cancelled: () -> Boolean) {
        if (dest.exists() && expected > 0 && dest.length() == expected) {
            progress.update(expected, expected, false)
            return
        }
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (expected > 0 && have > expected) {
            part.delete()
            have = 0
        }
        // 已完整下载但还没改名（例如上次恰好在改名前被停止）
        if (expected > 0 && have == expected) {
            finishFile(part, dest)
            progress.update(expected, expected, false)
            return
        }
        progress.update(have, expected, false)
        if (cancelled()) throw Cancelled()
        val c = open(url, have, "*/*")
        try {
            val append = c.responseCode == 206 && have > 0
            if (!append) have = 0
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (len > 0) have + len else expected
            c.inputStream.use { input ->
                openOut(part, append).use { out ->
                    copy(input, out, have, total, progress, cancelled)
                }
            }
            if (total > 0 && part.length() < total) throw IOException("连接中断（已下载 ${part.length()} / $total 字节）")
        } finally {
            conn = null
            c.disconnect()
        }
        finishFile(part, dest)
    }

    private fun finishFile(part: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw StorageException("无法保存文件：${dest.name}")
    }

    private fun openOut(f: File, append: Boolean): FileOutputStream = try {
        FileOutputStream(f, append)
    } catch (e: IOException) {
        throw StorageException("无法写入文件，请检查存储空间：${e.message}")
    }

    private fun write(out: OutputStream, buf: ByteArray, n: Int) {
        try {
            out.write(buf, 0, n)
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space", true)) throw StorageException("存储空间不足")
            throw StorageException("写入文件失败：$m")
        }
    }

    private fun copy(
        input: InputStream,
        out: OutputStream,
        start: Long,
        total: Long,
        progress: Progress,
        cancelled: () -> Boolean,
    ) {
        val buf = ByteArray(64 * 1024)
        var done = start
        var lastReport = start
        while (true) {
            if (cancelled()) throw Cancelled()
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                // abort() 断开连接会让 read 抛异常，这里统一视为停止
                if (cancelled()) throw Cancelled()
                throw e
            }
            if (n < 0) break
            write(out, buf, n)
            done += n
            if (done - lastReport >= 256 * 1024) {
                lastReport = done
                progress.update(done, total, false)
            }
        }
        progress.update(done, if (total > 0) total else done, false)
    }

    private fun fetchPages(t: DownloadTask, dir: File, extractPdf: Boolean, progress: Progress, cancelled: () -> Boolean) {
        val pagesDir = File(dir, Storage.PAGES_DIR)
        pagesDir.mkdirs()
        var n = t.pages
        if (n <= 0) n = api.chapterPages(t.chapterId)
        if (n <= 0) throw ApiException(0, "该章节没有可下载的页面")
        val existing = (pagesDir.list() ?: emptyArray())
            .filter { !it.endsWith(".part") }
            .map { it.substringBefore('.') }
            .toHashSet()
        var done = (0 until n).count { pageName(it) in existing }.toLong()
        progress.update(done, n.toLong(), true)
        for (i in 0 until n) {
            if (cancelled()) throw Cancelled()
            val name = pageName(i)
            if (name in existing) continue
            val c = open(api.pageUrl(t.chapterId, i, extractPdf), 0, "image/*")
            try {
                val ext = extForMime(c.contentType)
                val part = File(pagesDir, "$name.part")
                c.inputStream.use { input -> openOut(part, false).use { out -> copyPage(input, out, cancelled) } }
                if (part.length() == 0L) throw IOException("第 ${i + 1} 页为空")
                if (!part.renameTo(File(pagesDir, name + ext))) throw StorageException("无法保存第 ${i + 1} 页")
            } finally {
                conn = null
                c.disconnect()
            }
            done++
            progress.update(done, n.toLong(), true)
        }
    }

    private fun copyPage(input: InputStream, out: OutputStream, cancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (cancelled()) throw Cancelled()
            val r = try {
                input.read(buf)
            } catch (e: IOException) {
                if (cancelled()) throw Cancelled()
                throw e
            }
            if (r < 0) break
            write(out, buf, r)
        }
    }

    private fun validateZip(f: File) {
        try {
            ZipFile(f).use { z ->
                if (z.getEntry("META-INF/container.xml") == null) throw IOException("下载的 EPUB 缺少 container.xml")
            }
        } catch (e: IOException) {
            f.delete()
            throw IOException("EPUB 文件损坏：${e.message}")
        }
    }

    companion object {
        fun pageName(i: Int) = i.toString().padStart(5, '0')

        fun extForMime(mime: String?): String {
            val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return ".img"
            return when (m) {
                "image/jpeg", "image/jpg" -> ".jpg"
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                "image/avif" -> ".avif"
                "image/bmp" -> ".bmp"
                "image/jxl" -> ".jxl"
                else -> ".img"
            }
        }

        /**
         * 根据磁盘上的未完成文件估算进度（应用重启后、暂停项显示用）。
         * 返回 (done, total, pages)；无法估算时返回 null。
         */
        fun partialProgress(storage: Storage, t: DownloadTask): Triple<Long, Long, Boolean>? {
            val dir = storage.chapterDir(t.serverKey, t.seriesId, t.chapterId)
            if (!dir.isDirectory) return null
            val pagesDir = File(dir, Storage.PAGES_DIR)
            val pageFiles = (pagesDir.list() ?: emptyArray()).count { !it.endsWith(".part") }
            if (pageFiles > 0 && t.pages > 0) return Triple(pageFiles.toLong().coerceAtMost(t.pages.toLong()), t.pages.toLong(), true)
            val name = if (t.kind == Kind.EPUB) Storage.EPUB_FILE else Storage.PDF_FILE
            val part = File(dir, "$name.part")
            if (part.exists() && t.bytes > 0) return Triple(part.length().coerceAtMost(t.bytes), t.bytes, false)
            return null
        }
    }
}

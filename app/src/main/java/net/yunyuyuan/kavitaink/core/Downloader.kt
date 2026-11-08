package net.yunyuyuan.kavitaink.core

import net.yunyuyuan.kavitaink.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.zip.ZipFile

/** 本地存储问题（空间不足、无法写入），重试没有意义 */

/**
 * 下载单个章节到本地。纯 Kotlin，不依赖 Android，可在 JVM 上直接测试。
 *
 *  - EPUB / PDF / CBZ / ZIP：GET /api/Download/chapter 下载原文件到下载目录，保持服务器上的目录结构，
 *    支持 Range 断点续传（未完成部分保存在同目录的隐藏文件 .<文件名>.part）
 *  - 其他图片类（cbr/cb7/rar/图片文件夹、多文件章节）：逐页 GET /api/Reader/image 到私有目录，
 *    由服务器负责解压，客户端无需内置 rar/7z 解码，已下载的页会自动跳过
 *  - PDF 本机无法渲染、或 CBZ 实际不是 zip 时，自动改为逐页下载
 *
 * 暂停 / 取消：调用方让 stopped() 返回 true 并调用 [abort]。正在进行的连接会被立即断开，
 * 已经写入磁盘的数据全部保留，下次从断点继续；是否删除由调用方决定（取消才删）。
 */
class Downloader(
    private val api: KavitaApi,
    private val storage: Storage,
    private val libs: LibraryCache = LibraryCache(api),
    /** 逐页下载时同时下载的页数（1 = 一页一页下） */
    private val pageWorkers: Int = 1,
) {

    /** 因暂停或取消而中止（不是错误） */
    class Cancelled : Exception("stopped")

    fun interface Progress {
        /** pages = true 时 done/total 是页数，否则是字节数 */
        fun update(done: Long, total: Long, pages: Boolean)
    }

    /** 正在使用的连接（逐页并发下载时不止一个） */
    private val conns = java.util.Collections.synchronizedSet(HashSet<HttpURLConnection>())
    @Volatile private var aborted = false

    /** 可在任意线程调用：断开所有连接，让阻塞中的读取立即返回 */
    fun abort() {
        aborted = true
        disconnectAll()
    }

    private fun disconnectAll() {
        val list = synchronized(conns) { conns.toList() }
        list.forEach { runCatching { it.disconnect() } }
    }

    private fun release(c: HttpURLConnection) {
        conns.remove(c)
        runCatching { c.disconnect() }
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
            conns.clear()
        }
    }

    private fun doDownload(
        t: DownloadTask,
        progress: Progress,
        cancelled: () -> Boolean,
        pdfOk: (File) -> Boolean,
    ): Kind {
        if (t.serverKey != api.serverKey) throw ApiException(0, R.string.err_other_server)
        val dir = storage.chapterDir(t.serverKey, t.seriesId, t.chapterId)
        dir.mkdirs()
        if (!dir.isDirectory) throw StorageException(R.string.err_mkdir)
        storage.ensureSeries(t)
        if (cancelled()) throw Cancelled()
        saveCover(t)

        var kind = t.kind
        var path = ""
        // 多文件章节没有单一的原文件，改为逐页下载
        if (t.fileCount > 1 && kind != Kind.EPUB) kind = Kind.IMAGES
        when (kind) {
            Kind.EPUB -> {
                if (t.fileCount > 1) throw ApiException(0, R.string.err_multi_file_epub)
                path = targetPath(t, dir)
                val f = storage.contentFile(path)
                fetchOriginal(t, f, progress, cancelled)
                validateEpub(f)
            }
            Kind.PDF -> {
                if (hasPages(dir) && storage.readTarget(dir) == null) {
                    // 上次已经改为按页下载（本机无法渲染该 PDF），继续按页
                    kind = Kind.IMAGES
                    fetchPages(t, dir, true, progress, cancelled)
                } else {
                    path = targetPath(t, dir)
                    val f = storage.contentFile(path)
                    fetchOriginal(t, f, progress, cancelled)
                    if (!pdfOk(f)) {
                        discard(dir, f)
                        path = ""
                        kind = Kind.IMAGES
                        fetchPages(t, dir, true, progress, cancelled)
                    }
                }
            }
            Kind.ZIP -> {
                if (hasPages(dir) && storage.readTarget(dir) == null) {
                    kind = Kind.IMAGES
                    fetchPages(t, dir, false, progress, cancelled)
                } else {
                    path = targetPath(t, dir)
                    val f = storage.contentFile(path)
                    fetchOriginal(t, f, progress, cancelled)
                    // 扩展名是 cbz 但实际是 rar / 7z，或者压缩方式本机不支持：改为逐页下载
                    if (!zipReadable(f)) {
                        discard(dir, f)
                        path = ""
                        kind = Kind.IMAGES
                        fetchPages(t, dir, false, progress, cancelled)
                    }
                }
            }
            Kind.IMAGES -> fetchPages(t, dir, t.format == Fmt.PDF, progress, cancelled)
        }
        if (cancelled()) throw Cancelled()
        storage.writeChapterMeta(dir, t, kind, path)
        return kind
    }

    /** 原文件在下载目录里的相对路径；第一次计算后记在 target 里，续传时不再重新计算 */
    private fun targetPath(t: DownloadTask, dir: File): String {
        storage.readTarget(dir)?.let { return it }
        val lib = libs.libraryOf(t.seriesId)
        val fallbackName = Paths.sanitize(t.seriesName.ifBlank { "Kavita" }) + "/" + "${t.chapterId}${t.ext.ifBlank { ".bin" }}"
        val rel = if (t.filePath.isBlank()) {
            Paths.sanitize(lib?.name ?: "Kavita") + "/" + fallbackName
        } else {
            Paths.contentPath(lib?.name ?: "", t.filePath, lib?.folders ?: emptyList(), fallbackName)
        }
        storage.writeTarget(dir, rel)
        return rel
    }

    private fun fetchOriginal(t: DownloadTask, dest: File, progress: Progress, cancelled: () -> Boolean) {
        val parent = dest.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw StorageException(R.string.err_mkdir_content, parent.absolutePath)
        }
        fetchFile(api.downloadUrl(t.chapterId), dest, t.bytes, progress, cancelled, storage.partFile(dest))
    }

    /** 放弃已下载的原文件（改为逐页下载时） */
    private fun discard(dir: File, f: File) {
        f.delete()
        storage.partFile(f).delete()
        File(dir, Storage.TARGET).delete()
    }

    private fun zipReadable(f: File): Boolean = try {
        ZipPages.open(f).use { it.count > 0 }
    } catch (t: Throwable) {
        false
    }

    /**
     * 下载单个文件（断点续传：未完成的部分保存在 <dest>.part，下次从断点继续）。
     * 字体、应用更新等复用；expected ≤ 0 表示大小未知。
     */
    fun fetch(url: String, dest: File, expected: Long, progress: Progress, stopped: () -> Boolean) {
        val cancelled = { aborted || stopped() }
        try {
            fetchFile(url, dest, expected, progress, cancelled)
        } catch (e: IOException) {
            if (cancelled()) throw Cancelled()
            throw e
        } finally {
            conns.clear()
        }
    }

    private fun hasPages(dir: File): Boolean =
        (File(dir, Storage.PAGES_DIR).list() ?: emptyArray()).any { !it.endsWith(".part") }

    private fun saveCover(t: DownloadTask) {
        val cover = File(storage.seriesDir(t.serverKey, t.seriesId), Storage.COVER)
        if (cover.exists() && cover.length() > 0) return
        runCatching {
            val bytes = api.coverBytes(t.seriesId)
            if (bytes.isNotEmpty()) {
                val tmp = File(cover.parentFile, "cover.tmp" + Thread.currentThread().id)
                tmp.writeBytes(bytes)
                tmp.renameTo(cover)
            }
        }
    }

    private fun open(url: String, rangeFrom: Long, accept: String): HttpURLConnection {
        var opened: HttpURLConnection? = null
        val c = try {
            api.connect("GET", url, rangeFrom = rangeFrom, accept = accept, readTimeoutMs = 60_000, onOpen = {
                opened = it
                conns.add(it)
                if (aborted) runCatching { it.disconnect() }
            })
        } catch (e: IOException) {
            opened?.let(::release)
            if (aborted) throw Cancelled()
            throw e
        }
        if (aborted) {
            release(c)
            throw Cancelled()
        }
        return c
    }

    private fun fetchFile(
        url: String,
        dest: File,
        expected: Long,
        progress: Progress,
        cancelled: () -> Boolean,
        part: File = File(dest.path + ".part"),
    ) {
        if (dest.exists() && expected > 0 && dest.length() == expected) {
            progress.update(expected, expected, false)
            return
        }
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
            if (total > 0 && part.length() < total) throw LocalizedIOException(R.string.err_incomplete, part.length(), total)
        } finally {
            release(c)
        }
        finishFile(part, dest)
    }

    private fun finishFile(part: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw StorageException(R.string.err_save_file, dest.name)
    }

    private fun openOut(f: File, append: Boolean): FileOutputStream = try {
        FileOutputStream(f, append)
    } catch (e: IOException) {
        throw StorageException(R.string.err_write_space, e.message ?: "")
    }

    private fun write(out: OutputStream, buf: ByteArray, n: Int) {
        try {
            out.write(buf, 0, n)
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space", true)) throw StorageException(R.string.err_no_space)
            throw StorageException(R.string.err_write_failed, m)
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
        if (n <= 0) throw ApiException(0, R.string.err_no_pages)
        val existing = (pagesDir.list() ?: emptyArray())
            .filter { !it.endsWith(".part") }
            .map { it.substringBefore('.') }
            .toHashSet()
        val todo = (0 until n).filter { pageName(it) !in existing }
        val total = n.toLong()
        var reported = (n - todo.size).toLong()
        progress.update(reported, total, true)
        if (todo.isEmpty()) return

        val done = java.util.concurrent.atomic.AtomicLong(reported)
        val reportLock = Any()
        fun pageDone() {
            val d = done.incrementAndGet()
            // 多个线程同时完成时，进度只增不减
            synchronized(reportLock) {
                if (d > reported) {
                    reported = d
                    progress.update(d, total, true)
                }
            }
        }

        // 第一页单独下：服务器第一次读某个章节时要先解压整个压缩包，避免几个请求同时触发解压
        if (cancelled()) throw Cancelled()
        fetchPage(t, pagesDir, todo[0], extractPdf, cancelled)
        pageDone()
        if (todo.size == 1) return

        val rest = todo.subList(1, todo.size)
        val workers = pageWorkers.coerceIn(1, rest.size)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = { cancelled() || error.get() != null }
        val work = Runnable {
            while (!stop()) {
                val k = next.getAndIncrement()
                if (k >= rest.size) break
                try {
                    fetchPage(t, pagesDir, rest[k], extractPdf, stop)
                } catch (e: Throwable) {
                    // 第一个出错的线程记下原因并断开其他连接，其他线程随之停止
                    if (error.compareAndSet(null, e)) disconnectAll()
                    break
                }
                pageDone()
            }
        }
        val threads = (1 until workers).map { Thread(work, "kavita-page-$it").apply { start() } }
        work.run()
        threads.forEach { it.join() }
        if (cancelled()) throw Cancelled()
        error.get()?.let { throw it }
    }

    private fun fetchPage(t: DownloadTask, pagesDir: File, i: Int, extractPdf: Boolean, cancelled: () -> Boolean) {
        if (cancelled()) throw Cancelled()
        val name = pageName(i)
        val c = open(api.pageUrl(t.chapterId, i, extractPdf), 0, "image/*")
        try {
            val ext = extForMime(c.contentType)
            val part = File(pagesDir, "$name.part")
            c.inputStream.use { input -> openOut(part, false).use { out -> copyPage(input, out, cancelled) } }
            if (part.length() == 0L) throw LocalizedIOException(R.string.err_page_empty, i + 1)
            if (!part.renameTo(File(pagesDir, name + ext))) throw StorageException(R.string.err_save_page, i + 1)
        } catch (e: IOException) {
            if (cancelled()) throw Cancelled()
            throw e
        } finally {
            release(c)
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

    private fun validateEpub(f: File) {
        try {
            ZipFile(f).use { z ->
                if (z.getEntry("META-INF/container.xml") == null) throw LocalizedIOException(R.string.err_epub_no_container)
            }
        } catch (e: IOException) {
            f.delete()
            throw LocalizedIOException(R.string.err_epub_corrupt, e.message ?: "")
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
            val rel = storage.readTarget(dir) ?: return null
            val part = storage.partFile(storage.contentFile(rel))
            if (part.exists() && t.bytes > 0) return Triple(part.length().coerceAtMost(t.bytes), t.bytes, false)
            return null
        }
    }
}

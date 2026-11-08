package net.yunyuyuan.kavitaink.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 1.3.0：下载目录保持服务器原结构、CBZ 直接阅读、并发下载 */
class V130Test {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("v130").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun storage() = Storage(File(tmp, "index"), File(tmp, "Books/KavitaInk"))

    private fun task(
        serverKey: String = "srv",
        chapterId: Int = 11,
        pages: Int = 9,
        format: Int = Fmt.ARCHIVE,
        ext: String = ".cbr",
        bytes: Long = 0,
        filePath: String = "",
    ) = DownloadTask(
        serverKey, 7, "My Series", Fmt.ARCHIVE, chapterId, "第1卷", 0, 1.0, 0.0,
        pages, format, ext, bytes, 1, null, filePath,
    )

    // ---------- 路径映射 ----------

    @Test
    fun contentPathKeepsServerLayoutUnderLibraryName() {
        val folders = listOf("/data/manga")
        assertEquals(
            "漫画/One Piece/One Piece v01.cbz",
            Paths.contentPath("漫画", "/data/manga/One Piece/One Piece v01.cbz", folders, "x.cbz"),
        )
        // 更深的层级原样保留
        assertEquals(
            "漫画/Publisher/One Piece/Vol 1/c001.cbz",
            Paths.contentPath("漫画", "/data/manga/Publisher/One Piece/Vol 1/c001.cbz", folders, "x"),
        )
        // 书库有多个根目录（含嵌套）：取最长的匹配
        assertEquals(
            "Books/Novel.epub",
            Paths.contentPath("Books", "/data/books/light/Novel.epub", listOf("/data/books", "/data/books/light"), "x"),
        )
        // 根目录末尾带斜杠、多余斜杠
        assertEquals("L/A/b.pdf", Paths.contentPath("L", "/srv//lib/A/b.pdf", listOf("/srv/lib/"), "x"))
    }

    @Test
    fun windowsServerPathsAreMatchedCaseInsensitively() {
        assertEquals(
            "Comics/Batman/Batman 001.cbz",
            Paths.contentPath("Comics", "D:\\Media\\Comics\\Batman\\Batman 001.cbz", listOf("d:/media/comics"), "x"),
        )
    }

    @Test
    fun fallsBackToParentFolderAndFileName() {
        // 拿不到书库根目录（例如普通账号）或路径不在任何根目录下
        assertEquals("漫画/One Piece/v01.cbz", Paths.contentPath("漫画", "/x/y/One Piece/v01.cbz", emptyList(), "z"))
        assertEquals("漫画/One Piece/v01.cbz", Paths.contentPath("漫画", "/x/y/One Piece/v01.cbz", listOf("/other"), "z"))
        // 书库名未知
        assertEquals("Kavita/One Piece/v01.cbz", Paths.contentPath("", "/x/One Piece/v01.cbz", emptyList(), "z"))
    }

    @Test
    fun segmentsAreSanitized() {
        assertEquals("a_b_c_d_e_f_g_h_i", Paths.sanitize("a\\b/c:d*e?f\"g<h>i"))
        assertEquals("Vol. 1", Paths.sanitize(" Vol. 1. "))
        assertEquals("_", Paths.sanitize(".."))
        assertEquals("_", Paths.sanitize("   "))
        assertEquals("A_B", Paths.sanitize("A\u0001B"))
        // 书库名里的斜杠不能变成多一层目录
        assertEquals("Manga_Comics/x/y.cbz", Paths.contentPath("Manga/Comics", "/m/x/y.cbz", listOf("/m"), "z"))
        // 超长文件名：按 UTF-8 字节截断，保留扩展名，不截出半个汉字
        val long = "长".repeat(200) + ".cbz"
        val s = Paths.sanitize(long)
        assertTrue(s.endsWith(".cbz"))
        assertTrue(s.toByteArray(Charsets.UTF_8).size <= Paths.MAX_SEGMENT_BYTES)
        assertTrue(s.removeSuffix(".cbz").all { it == '长' })
        // 临时文件是同目录的隐藏文件
        assertEquals(".One Piece v01.cbz.part", Paths.partName("One Piece v01.cbz"))
    }

    @Test
    fun cbzAndZipAreKeptAsOriginalFiles() {
        assertEquals(Kind.ZIP, kindOf(Fmt.ARCHIVE, ".cbz"))
        assertEquals(Kind.ZIP, kindOf(Fmt.ARCHIVE, ".ZIP"))
        assertEquals(Kind.IMAGES, kindOf(Fmt.ARCHIVE, ".cbr"))
        assertEquals(Kind.IMAGES, kindOf(Fmt.ARCHIVE, ".cb7"))
        assertEquals(Kind.IMAGES, kindOf(Fmt.IMAGE, ".jpg"))
        assertEquals(Kind.EPUB, kindOf(Fmt.EPUB, ".epub"))
        assertEquals(Kind.PDF, kindOf(Fmt.PDF, ".pdf"))
    }

    // ---------- CBZ 读页 ----------

    private fun zip(f: File, names: List<String>, charset: Charset = Charsets.UTF_8) {
        ZipOutputStream(f.outputStream(), charset).use { z ->
            for (n in names) {
                z.putNextEntry(ZipEntry(n))
                if (!n.endsWith("/")) z.write(n.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }

    @Test
    fun zipPagesAreImagesInNaturalOrder() {
        val f = File(tmp, "a.cbz")
        zip(f, listOf("10.jpg", "2.jpg", "1.jpg", "__MACOSX/._1.jpg", ".hidden.jpg", "notes.txt", "ComicInfo.xml", "sub/", "sub/3.PNG", "Page 002.webp"))
        ZipPages.open(f).use { z ->
            val names = (0 until z.count).map { z.name(it) }
            assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "Page 002.webp", "sub/3.PNG"), names)
            assertArrayEquals("10.jpg".toByteArray(), z.bytes(2))
        }
        assertTrue(ZipPages.naturalCompare("c2", "c10") < 0)
        assertTrue(ZipPages.naturalCompare("c010", "c9") > 0)
        assertTrue(ZipPages.naturalCompare("A1", "a1") == 0)
    }

    @Test
    fun zipWithGbkFileNamesOpens() {
        val f = File(tmp, "gbk.cbz")
        zip(f, listOf("第1话/002.jpg", "第1话/001.jpg"), Charset.forName("GBK"))
        ZipPages.open(f).use { z ->
            assertEquals(2, z.count)
            assertEquals("第1话/001.jpg", z.name(0))
        }
    }

    @Test
    fun notAZipOrNoImagesIsRejected() {
        val rar = File(tmp, "fake.cbz").apply { writeBytes("Rar!\u001a\u0007\u0000 not a zip".toByteArray()) }
        try {
            ZipPages.open(rar).close()
            fail("rar 伪装的 cbz 应打不开")
        } catch (e: IOException) {
        }
        val empty = File(tmp, "empty.cbz")
        zip(empty, listOf("readme.txt"))
        try {
            ZipPages.open(empty).close()
            fail("没有图片的压缩包应报错")
        } catch (e: IOException) {
        }
    }

    // ---------- 存储索引 ----------

    @Test
    fun chapterIndexPointsToOriginalFile() {
        val st = storage()
        val t = task(format = Fmt.ARCHIVE, ext = ".cbz", filePath = "/data/manga/My Series/My Series v01.cbz")
        val dir = st.chapterDir(t.serverKey, t.seriesId, t.chapterId)
        st.ensureSeries(t)
        val rel = "漫画/My Series/My Series v01.cbz"
        val f = st.contentFile(rel)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(1000))
        // 用户自己放在同一文件夹的文件
        val mine = File(f.parentFile, "my notes.txt").apply { writeText("keep") }
        st.writeChapterMeta(dir, t, Kind.ZIP, rel)

        val c = st.readChapter(dir)!!
        assertEquals(f.canonicalPath, c.file!!.canonicalPath)
        assertEquals(Kind.ZIP, c.kind)
        assertEquals(1, st.listShelf().size)
        assertTrue(st.sizeOf(c) >= 1000)

        // 删除：只删本应用下载的文件；文件夹里还有别的文件时保留文件夹
        st.deleteChapter(c)
        assertFalse(f.exists())
        assertTrue(mine.exists())
        assertTrue(f.parentFile.isDirectory)
        assertTrue(st.listShelf().isEmpty())

        // 文件夹空了就一路删到下载目录为止
        mine.delete()
        f.writeBytes(ByteArray(10))
        st.ensureSeries(t)
        st.writeChapterMeta(dir, t, Kind.ZIP, rel)
        st.deleteChapter(st.readChapter(dir)!!)
        assertFalse(File(st.contentRoot, "漫画").exists())
        assertTrue(st.contentRoot.isDirectory)
    }

    @Test
    fun originalRemovedByAnotherAppMeansNotDownloaded() {
        val st = storage()
        val t = task(format = Fmt.PDF, ext = ".pdf")
        val dir = st.chapterDir(t.serverKey, t.seriesId, t.chapterId)
        st.ensureSeries(t)
        val f = st.contentFile("书/A/A.pdf").apply { parentFile.mkdirs(); writeBytes(ByteArray(5)) }
        st.writeChapterMeta(dir, t, Kind.PDF, "书/A/A.pdf")
        assertTrue(st.isComplete(t.serverKey, t.seriesId, t.chapterId))
        f.delete()
        assertFalse(st.isComplete(t.serverKey, t.seriesId, t.chapterId))
        assertNull(st.readChapter(dir))
        assertTrue(st.listShelf().isEmpty())
    }

    @Test
    fun partialDownloadProgressAndCleanup() {
        val st = storage()
        val t = task(format = Fmt.ARCHIVE, ext = ".cbz", bytes = 1000)
        val dir = st.chapterDir(t.serverKey, t.seriesId, t.chapterId).apply { mkdirs() }
        val rel = "漫画/My Series/v01.cbz"
        st.writeTarget(dir, rel)
        val part = st.partFile(st.contentFile(rel)).apply { parentFile.mkdirs(); writeBytes(ByteArray(300)) }
        assertTrue(part.name.startsWith("."))
        assertEquals(Triple(300L, 1000L, false), Downloader.partialProgress(st, t))
        // 取消：删掉临时文件、空文件夹和索引
        st.deletePartial(t.serverKey, t.seriesId, t.chapterId)
        assertFalse(part.exists())
        assertFalse(File(st.contentRoot, "漫画").exists())
        assertFalse(dir.exists())
    }

    // ---------- 并发下载（本地模拟服务器） ----------

    /** 最小 HTTP 服务（Android 单元测试的类路径里没有 com.sun.net.httpserver） */
    private class Resp(val code: Int, val body: ByteArray = ByteArray(0), val type: String = "application/octet-stream")

    private class MiniServer(val handler: (path: String, query: Map<String, String>, headers: Map<String, String>) -> Resp) {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        val port get() = socket.localPort

        fun start() {
            Thread {
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (e: IOException) { break }
                    pool.execute { serve(c) }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(c: Socket) = c.use {
            val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = inp.readLine() ?: return@use
            val headers = HashMap<String, String>()
            while (true) {
                val h = inp.readLine() ?: break
                if (h.isEmpty()) break
                headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
            }
            val target = line.split(' ')[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val r = try { handler(path, query, headers) } catch (t: Throwable) { Resp(500) }
            val out = c.getOutputStream()
            val head = "HTTP/1.1 ${r.code} X\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n\r\n"
            runCatching {
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(r.body)
                out.flush()
            }
        }

        fun stop() {
            runCatching { socket.close() }
            pool.shutdownNow()
        }
    }

    private var server: MiniServer? = null
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    private val requests = Collections.synchronizedList(ArrayList<String>())
    /** 返回 500 的页 */
    @Volatile private var failPage = -1
    @Volatile private var pageDelayMs = 250L
    private val events = Collections.synchronizedList(ArrayList<Pair<String, Int>>())
    @Volatile private var cbzBytes = ByteArray(0)

    private fun startServer(): KavitaApi {
        val srv = MiniServer { path, q, headers ->
            when (path) {
                "/api/Reader/image" -> {
                    val page = q["page"]!!.toInt()
                    requests += "page=$page"
                    val n = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                    events += "start" to page
                    Thread.sleep(pageDelayMs)
                    // 先记“结束”再回复：客户端收到回复后才可能发起下一个请求，计数不会多算
                    events += "end" to page
                    inFlight.decrementAndGet()
                    if (page == failPage) Resp(500, "boom".toByteArray())
                    else Resp(200, "page-$page".toByteArray(), "image/png")
                }
                "/api/Download/chapter" -> {
                    requests += "download"
                    val from = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                    Resp(if (from > 0) 206 else 200, cbzBytes.copyOfRange(from.toInt(), cbzBytes.size))
                }
                // 封面、书库信息都返回 404：下载应照常进行（书库未知时退回「父文件夹/文件名」）
                else -> Resp(404)
            }
        }
        srv.start()
        server = srv
        return KavitaApi("http://127.0.0.1:${srv.port}", "key")
    }

    @After
    fun stopServer() {
        server?.stop()
    }

    @Test
    fun pagesDownloadConcurrentlyAfterTheFirstOne() {
        val api = startServer()
        val st = storage()
        val t = task(serverKey = api.serverKey, pages = 9)
        val reports = Collections.synchronizedList(ArrayList<Long>())
        val t0 = System.currentTimeMillis()
        val kind = Downloader(api, st, LibraryCache(api), 3).download(t, { d, _, p -> assertTrue(p); reports += d }, { false })
        val ms = System.currentTimeMillis() - t0
        assertEquals(Kind.IMAGES, kind)
        val pages = File(st.chapterDir(t.serverKey, t.seriesId, t.chapterId), Storage.PAGES_DIR).list()!!.sorted()
        assertEquals((0 until 9).map { "%05d.png".format(it) }, pages)
        println("9 页 / 3 并发：${ms}ms，最大同时请求 ${maxInFlight.get()}")
        assertEquals(3, maxInFlight.get())
        // 第一页单独请求，结束后才开始其他页（让服务器先解压好）
        assertEquals("start" to 0, events[0])
        assertEquals("end" to 0, events[1])
        // 1 + ceil(8/3) = 4 轮，远小于顺序下载的 9 轮
        assertTrue("并发应明显快于顺序下载：${ms}ms", ms < 9 * pageDelayMs * 0.75)
        // 进度只增不减，最后是 9
        assertEquals(reports.sorted(), reports)
        assertEquals(9L, reports.last())
        assertTrue(st.isComplete(t.serverKey, t.seriesId, t.chapterId))
    }

    @Test
    fun onePageFailingStopsTheOthersAndResumeFetchesOnlyMissingPages() {
        val api = startServer()
        val st = storage()
        val t = task(serverKey = api.serverKey, pages = 12)
        failPage = 5
        try {
            Downloader(api, st, LibraryCache(api), 3).download(t, { _, _, _ -> }, { false })
            fail("应抛出服务器错误")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertTrue(KavitaApi.isTransient(e))
        }
        assertFalse(st.isComplete(t.serverKey, t.seriesId, t.chapterId))
        val pagesDir = File(st.chapterDir(t.serverKey, t.seriesId, t.chapterId), Storage.PAGES_DIR)
        val kept = pagesDir.list()!!.filter { !it.endsWith(".part") }.toSet()
        println("出错后保留 ${kept.size} 页：$kept")
        assertFalse("%05d.png".format(5) in kept)
        assertTrue(kept.size < 12)
        // 出错后不再发起新的请求：最多是出错时已经在途的那几个
        assertTrue("请求数 ${requests.size}", requests.size <= 1 + 5 + 3)

        failPage = -1
        requests.clear()
        Downloader(api, st, LibraryCache(api), 3).download(t, { _, _, _ -> }, { false })
        assertEquals("续传只请求缺的页", 12 - kept.size, requests.size)
        assertEquals(12, pagesDir.list()!!.count { !it.endsWith(".part") })
        assertTrue(st.isComplete(t.serverKey, t.seriesId, t.chapterId))
    }

    @Test
    fun pausingStopsAllPageWorkers() {
        val api = startServer()
        val st = storage()
        val t = task(serverKey = api.serverKey, pages = 40)
        pageDelayMs = 150
        val dl = Downloader(api, st, LibraryCache(api), 4)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        var err: Throwable? = null
        val th = Thread {
            try {
                dl.download(t, { d, _, _ -> if (d >= 6) stop.set(true) }, { stop.get() })
            } catch (e: Throwable) {
                err = e
            }
        }
        th.start()
        th.join(10_000)
        assertFalse(th.isAlive)
        assertTrue("暂停应以 Cancelled 结束：$err", err is Downloader.Cancelled)
        val n = requests.size
        Thread.sleep(500)
        assertEquals("暂停后不应再有新请求", n, requests.size)
        assertTrue("应远没下完：$n", n < 40)
        assertFalse(st.isComplete(t.serverKey, t.seriesId, t.chapterId))
        assertNotNull(Downloader.partialProgress(st, t))
    }

    @Test
    fun cbzIsSavedAsIsUnderFallbackFolderWhenLibraryUnknown() {
        val api = startServer()
        val src = File(tmp, "src.cbz")
        zip(src, listOf("001.jpg", "002.jpg", "003.jpg"))
        cbzBytes = src.readBytes()
        val st = storage()
        val t = task(
            serverKey = api.serverKey, pages = 3, ext = ".cbz", bytes = cbzBytes.size.toLong(),
            filePath = "/data/manga/My Series/My Series v01.cbz",
        )
        // 先放一个前 100 字节的临时文件，验证续传
        val dest = st.contentFile("Kavita/My Series/My Series v01.cbz")
        st.partFile(dest).apply { parentFile.mkdirs(); writeBytes(cbzBytes.copyOf(100)) }
        st.writeTarget(st.chapterDir(t.serverKey, t.seriesId, t.chapterId).apply { mkdirs() }, "Kavita/My Series/My Series v01.cbz")
        val kind = Downloader(api, st, LibraryCache(api), 2).download(t, { _, _, _ -> }, { false })
        assertEquals(Kind.ZIP, kind)
        assertArrayEquals("与服务器文件逐字节一致", cbzBytes, dest.readBytes())
        assertFalse(st.partFile(dest).exists())
        assertEquals(listOf("download"), requests.toList())
        val c = st.readChapter(st.chapterDir(t.serverKey, t.seriesId, t.chapterId))!!
        ZipPages.open(c.zipFile).use { assertEquals(3, it.count) }
        // 下载目录里只有原文件，没有索引、封面等其他东西
        val all = st.contentRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(st.contentRoot).path }.toList()
        assertEquals(listOf("Kavita/My Series/My Series v01.cbz"), all)
    }

    @Test
    fun fakeCbzFallsBackToPageByPage() {
        val api = startServer()
        cbzBytes = ByteArrayOutputStream().apply { write("Rar!\u001a\u0007\u0000".toByteArray()); write(ByteArray(500)) }.toByteArray()
        val st = storage()
        val t = task(serverKey = api.serverKey, pages = 4, ext = ".cbz", bytes = cbzBytes.size.toLong(), filePath = "/m/S/S v01.cbz")
        val kind = Downloader(api, st, LibraryCache(api), 2).download(t, { _, _, _ -> }, { false })
        assertEquals("扩展名是 cbz 但实际是 rar：改为逐页下载", Kind.IMAGES, kind)
        assertEquals(4, File(st.chapterDir(t.serverKey, t.seriesId, t.chapterId), Storage.PAGES_DIR).list()!!.size)
        // 放弃的原文件不留在下载目录
        assertTrue(st.contentRoot.walkTopDown().none { it.isFile })
    }
}

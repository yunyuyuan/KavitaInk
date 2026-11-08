package net.yunyuyuan.kavitaink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 端到端测试：需要真实 Kavita 服务器。
 *   KAVITA_URL=http://localhost:5000 KAVITA_USER=admin KAVITA_PASS=xxx ./gradlew testDebugUnitTest
 * 未设置环境变量时自动跳过。
 * 测试服和测试在同一台机器上时，还会把下载结果和服务器上的原文件逐字节比较。
 */
class LiveServerTest {

    private fun env(k: String) = System.getenv(k).orEmpty()

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun loginBrowseDownloadAndReadOffline() {
        val url = env("KAVITA_URL")
        assumeTrue("未配置 KAVITA_URL，跳过联调测试", url.isNotBlank())

        // 1. 用户名密码登录换取 Auth Key
        val api = KavitaApi.login(url, env("KAVITA_USER"), env("KAVITA_PASS"))
        assertTrue(api.apiKey.isNotBlank())
        println("Kavita version = ${api.version()}, serverKey=${api.serverKey}")

        // 2. 直接用 Auth Key 也能访问
        val api2 = KavitaApi(url + "/api/opds/" + api.apiKey, KavitaApi.keyFromOpdsUrl(url + "/api/opds/" + api.apiKey)!!)
        assertEquals(api.base, api2.base)
        val libs = api2.libraries()
        println("libraries = ${libs.map { "${it.id}:${it.name}:${it.folders}" }}")
        assertTrue(libs.isNotEmpty())
        assertTrue("管理员账号应能拿到书库根目录", libs.all { it.folders.isNotEmpty() })

        val tmp = Files.createTempDirectory("kavitaink").toFile()
        val storage = Storage(File(tmp, "index"), File(tmp, "Books/KavitaInk"))
        val cache = LibraryCache(api)
        val seen = HashSet<Kind>()
        var compared = 0

        for (lib in libs) {
            var page = 1
            while (true) {
                val sp = api.seriesPage(lib.id, page, 2)
                for (s in sp.items) {
                    val chapters = api.chapters(s.id, s.format)
                    println("  [${lib.name}] ${s.name} (fmt=${s.format}) -> ${chapters.take(3).map { "${it.id}:${it.kind}:${it.filePath}" }}")
                    // 每个系列最多下 3 章，够覆盖各种格式
                    for (c in chapters.take(3)) {
                        val task = DownloadTask.of(api.serverKey, s.name, s.format, c)
                        var reports = 0
                        val kind = Downloader(api, storage, cache, 3).download(task, { _, _, _ -> reports++ }, { false })
                        seen += kind
                        assertTrue(reports > 0)
                        assertTrue(storage.isComplete(api.serverKey, s.id, c.id))
                        val local = storage.readChapter(storage.chapterDir(api.serverKey, s.id, c.id))!!
                        when (kind) {
                            Kind.IMAGES -> {
                                assertNull(local.file)
                                assertEquals(c.pages, local.pagesDir.list()!!.size)
                            }
                            else -> {
                                val f = local.file!!
                                // 保持服务器结构：<下载目录>/<书库名>/<相对书库根目录的路径>，文件名不变
                                val root = lib.folders.first { c.filePath.startsWith(it.trimEnd('/') + "/") }
                                val rel = c.filePath.removePrefix(root.trimEnd('/') + "/")
                                assertEquals(File(storage.contentRoot, lib.name + "/" + rel).canonicalPath, f.canonicalPath)
                                assertEquals(c.bytes, f.length())
                                val origin = File(c.filePath)
                                if (origin.isFile) {
                                    assertEquals("与服务器原文件逐字节一致：$rel", sha(origin), sha(f))
                                    compared++
                                }
                                when (kind) {
                                    Kind.EPUB -> EpubBook(f).use { b -> assertTrue(b.spine.isNotEmpty()) }
                                    Kind.ZIP -> ZipPages.open(f).use { z -> assertEquals(c.pages, z.count) }
                                    else -> {}
                                }
                                // 下载目录里不应残留临时文件
                                assertTrue(f.parentFile.list()!!.none { it.endsWith(".part") })
                            }
                        }
                    }
                }
                if (page >= sp.totalPages) break
                page++
            }
        }
        println("seen=$seen，逐字节比较 $compared 个文件")
        assertTrue("应覆盖 ZIP（cbz）、EPUB、PDF 原文件: $seen", seen.containsAll(listOf(Kind.ZIP, Kind.EPUB, Kind.PDF)))
        // 下载目录第一层就是书库名
        val top = storage.contentRoot.list()!!.toSet()
        println("下载目录第一层 = $top")
        assertTrue(top.all { n -> libs.any { it.name == n } })

        val shelf = storage.listShelf()
        println("shelf = ${shelf.map { "${it.name}(${it.chapters.size}) cover=${it.cover.length()}" }}")
        assertTrue(shelf.size >= 3)
        assertTrue(shelf.all { it.cover.length() > 0 })

        // 3. 断点续传：删掉一个 EPUB，先放一个前 50 KB 的临时文件，再继续下载
        val epubSeries = shelf.first { s -> s.chapters.any { it.kind == Kind.EPUB } }
        val epubChapter = epubSeries.chapters.first { it.kind == Kind.EPUB }
        val epubPath = epubChapter.file!!
        storage.deleteChapter(epubChapter)
        assertTrue(!epubPath.exists())
        val task = epubChapter.meta
        val part = storage.partFile(epubPath)
        part.parentFile.mkdirs()
        part.writeBytes(api.connect("GET", api.downloadUrl(task.chapterId), accept = "*/*").let { c ->
            try { c.inputStream.use { it.readBytes() }.copyOf(50_000) } finally { c.disconnect() }
        })
        var first = -1L
        Downloader(api, storage, cache).download(task, { d, _, _ -> if (first < 0) first = d }, { false })
        assertEquals("应从 50 KB 处续传", 50_000L, first)
        val resumed = storage.readChapter(storage.chapterDir(task.serverKey, task.seriesId, task.chapterId))!!
        assertEquals(epubPath.canonicalPath, resumed.file!!.canonicalPath)
        assertEquals(task.bytes, resumed.epubFile.length())
        assertTrue(!part.exists())
        EpubBook(resumed.epubFile).use { assertTrue(it.toc.isNotEmpty()) }

        // 4. 原文件被其他应用删掉后，章节自动视为未下载
        resumed.file!!.delete()
        assertNull(storage.readChapter(resumed.dir))

        // 5. 逐页下载（多文件章节 / 无法直接读的格式走这条路）：3 页并发，结果完整
        val zipCh = shelf.flatMap { it.chapters }.first { it.kind == Kind.ZIP }
        storage.deleteChapter(zipCh)
        val paged = zipCh.meta.copy(fileCount = 2)
        assertEquals(Kind.IMAGES, Downloader(api, storage, cache, 3).download(paged, { _, _, _ -> }, { false }))
        val pl = storage.readChapter(storage.chapterDir(paged.serverKey, paged.seriesId, paged.chapterId))!!
        assertEquals(paged.pages, pl.pagesDir.list()!!.count { !it.endsWith(".part") })
        storage.deleteChapter(pl)

        // 6. 取消：只删本次的临时文件，不留空文件夹
        val manga = shelf.first { s -> s.chapters.any { it.kind == Kind.ZIP } }.chapters.first { it.kind == Kind.ZIP }
        val mangaFile = manga.file!!
        storage.deleteChapter(manga)
        var cancelled = false
        try {
            var n = 0
            Downloader(api, storage, cache).download(manga.meta, { _, _, _ -> n++ }, { n >= 2 })
        } catch (e: Downloader.Cancelled) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(!storage.isComplete(manga.serverKey, manga.seriesId, manga.chapterId))
        storage.deletePartial(manga.serverKey, manga.seriesId, manga.chapterId)
        assertTrue(!storage.partFile(mangaFile).exists())
        assertNotNull(storage.contentRoot.list())

        // 7. 搜索
        val found = api.search("alice")
        println("search alice -> $found")
        assertTrue(found.isNotEmpty())

        tmp.deleteRecursively()
    }
}

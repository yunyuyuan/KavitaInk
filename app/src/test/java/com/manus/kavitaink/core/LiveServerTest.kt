package com.manus.kavitaink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 端到端测试：需要真实 Kavita 服务器。
 *   KAVITA_URL=http://localhost:5000 KAVITA_USER=admin KAVITA_PASS=xxx ./gradlew testDebugUnitTest
 * 未设置环境变量时自动跳过。
 */
class LiveServerTest {

    private fun env(k: String) = System.getenv(k).orEmpty()

    @Test
    fun loginBrowseDownloadAndReadOffline() {
        val url = env("KAVITA_URL")
        assumeTrue("未配置 KAVITA_URL，跳过联调测试", url.isNotBlank())

        // 1. 用户名密码登录换取 Auth Key
        val api = KavitaApi.login(url, env("KAVITA_USER"), env("KAVITA_PASS"))
        assertTrue(api.apiKey.isNotBlank())
        val version = api.version()
        println("Kavita version = $version, serverKey=${api.serverKey}")

        // 2. 直接用 Auth Key 也能访问
        val api2 = KavitaApi(url + "/api/opds/" + api.apiKey, KavitaApi.keyFromOpdsUrl(url + "/api/opds/" + api.apiKey)!!)
        assertEquals(api.base, api2.base)
        val libs = api2.libraries()
        println("libraries = $libs")
        assertTrue(libs.isNotEmpty())

        val root = Files.createTempDirectory("kavitaink").toFile()
        val storage = Storage(root)
        val seen = HashSet<Kind>()

        for (lib in libs) {
            var page = 1
            while (true) {
                val sp = api.seriesPage(lib.id, page, 2)
                for (s in sp.items) {
                    val chapters = api.chapters(s.id, s.format)
                    println("  [${lib.name}] ${s.name} (fmt=${s.format}) -> ${chapters.map { "${it.id}:${it.label}:${it.kind}" }}")
                    for (c in chapters) {
                        val task = DownloadTask.of(api.serverKey, s.name, s.format, c)
                        var reports = 0
                        val kind = Downloader(api, storage).download(task, { _, _, _ -> reports++ }, { false })
                        seen += kind
                        assertTrue(reports > 0)
                        assertTrue(storage.isComplete(api.serverKey, s.id, c.id))
                        val local = storage.readChapter(storage.chapterDir(api.serverKey, s.id, c.id))!!
                        when (kind) {
                            Kind.IMAGES -> assertEquals(c.pages, local.pagesDir.list()!!.size)
                            Kind.EPUB -> EpubBook(local.epubFile).use { b -> assertTrue(b.spine.isNotEmpty()) }
                            Kind.PDF -> assertEquals(c.bytes, local.pdfFile.length())
                        }
                    }
                }
                if (page >= sp.totalPages) break
                page++
            }
        }
        assertTrue("应覆盖图片、EPUB、PDF 三种离线形式: $seen", seen.containsAll(listOf(Kind.IMAGES, Kind.EPUB, Kind.PDF)))

        val shelf = storage.listShelf()
        println("shelf = ${shelf.map { "${it.name}(${it.chapters.size}) cover=${it.cover.length()}" }}")
        assertTrue(shelf.size >= 3)
        assertTrue(shelf.all { it.cover.length() > 0 })

        // 3. 断点续传：先取消一个 EPUB 下载，再继续
        val epubSeries = shelf.first { s -> s.chapters.any { it.kind == Kind.EPUB } }
        val epubChapter = epubSeries.chapters.first { it.kind == Kind.EPUB }
        storage.deleteChapter(epubChapter)
        val task = epubChapter.meta
        val part = File(storage.chapterDir(task.serverKey, task.seriesId, task.chapterId), Storage.EPUB_FILE + ".part")
        part.parentFile.mkdirs()
        part.writeBytes(api.connect("GET", api.downloadUrl(task.chapterId), accept = "*/*").let { c ->
            try { c.inputStream.use { it.readBytes() }.copyOf(50_000) } finally { c.disconnect() }
        })
        Downloader(api, storage).download(task, { _, _, _ -> }, { false })
        val resumed = storage.readChapter(storage.chapterDir(task.serverKey, task.seriesId, task.chapterId))!!
        assertEquals(task.bytes, resumed.epubFile.length())
        EpubBook(resumed.epubFile).use { assertTrue(it.toc.isNotEmpty()) }

        // 4. 取消
        val manga = shelf.first { s -> s.chapters.any { it.kind == Kind.IMAGES } }.chapters.first()
        storage.deleteChapter(manga)
        var cancelled = false
        try {
            var n = 0
            Downloader(api, storage).download(manga.meta, { _, _, _ -> n++ }, { n >= 2 })
        } catch (e: Downloader.Cancelled) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(!storage.isComplete(manga.serverKey, manga.seriesId, manga.chapterId))
        storage.deletePartial(manga.serverKey, manga.seriesId, manga.chapterId)

        // 5. 搜索
        val found = api.search("alice")
        println("search alice -> $found")
        assertTrue(found.isNotEmpty())

        root.deleteRecursively()
    }
}

package net.yunyuyuan.kavitaink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    private val ZH = XmlTexts("zh")

    private fun res(name: String): File = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Test
    fun mangaVolumesAreLabelledByVolume() {
        val list = Parsers.chapters(res("volumes_2.json").readText(), 2, Fmt.ARCHIVE)
        assertEquals(2, list.size)
        assertEquals("第1卷", list[0].title(ZH))
        assertEquals("第2卷", list[1].title(ZH))
        // cbz 直接下载原文件，阅读时从压缩包里读页
        assertEquals(Kind.ZIP, list[0].kind)
        assertEquals(".cbz", list[0].ext)
        assertEquals("/home/ubuntu/library/manga/Test Manga/Test Manga v01.cbz", list[0].filePath)
        assertEquals(5, list[0].pages)
        assertTrue(list[0].bytes > 0)
    }

    @Test
    fun singleEpubIsSpecialWithBookTitle() {
        val list = Parsers.chapters(res("volumes_4.json").readText(), 4, Fmt.EPUB)
        assertEquals(1, list.size)
        assertEquals("Alice's Adventures in Wonderland", list[0].title(ZH))
        assertEquals(Kind.EPUB, list[0].kind)
        assertEquals(2, list[0].group)
    }

    @Test
    fun pdfIsDetected() {
        val list = Parsers.chapters(res("volumes_1.json").readText(), 1, Fmt.PDF)
        assertEquals(1, list.size)
        assertEquals("PDF Book", list[0].title(ZH))
        assertEquals(Kind.PDF, list[0].kind)
    }

    @Test
    fun chapterLabelsForLooseAndVolumeChapters() {
        val json = """
        [{"id":1,"name":"3","minNumber":3,"chapters":[
            {"id":10,"range":"21","title":"21","titleName":"决战","isSpecial":false,"pages":20,"sortOrder":21,"format":1,"files":[{"bytes":100,"extension":".cbz","format":1}]}]},
         {"id":2,"name":"-100000","minNumber":-100000,"chapters":[
            {"id":11,"range":"25","title":"25","titleName":null,"isSpecial":false,"pages":18,"sortOrder":25,"format":1,"files":[{"bytes":100,"extension":".cbr","format":1}]}]}]
        """.trimIndent()
        val list = Parsers.chapters(json, 9, Fmt.ARCHIVE)
        assertEquals("第3卷 第21话 · 决战", list[0].title(ZH))
        assertEquals("第25话", list[1].title(ZH))
    }

    @Test
    fun alicePublicDomainEpub() {
        EpubBook(res("alice.epub")).use { b ->
            assertTrue(b.spine.size >= 10)
            assertTrue(b.toc.size >= 10)
            assertTrue(b.toc.any { it.title.contains("CHAPTER I", ignoreCase = true) || it.title.contains("Rabbit") })
            assertTrue(b.toc.all { b.exists(it.path) })
            assertNotNull(b.read(b.spine[0]))
            assertEquals("application/xhtml+xml", b.mimeOf(b.spine[1]))
        }
    }

    @Test
    fun chineseEpubNavAndResources() {
        EpubBook(res("novel_zh.epub")).use { b ->
            assertEquals("测试小说", b.title)
            assertEquals(3, b.spine.size)
            assertEquals(listOf("第1章", "第2章", "第3章"), b.toc.map { it.title })
            assertEquals("OEBPS/Text/ch2.xhtml", b.toc[1].path)
            assertEquals(1, b.spineIndexOf(b.toc[1].path))
            assertNotNull(b.read(EpubBook.resolve("OEBPS/Text", "../Images/pic.jpg")))
            assertTrue(b.isSpineDoc("/OEBPS/Text/ch1.xhtml"))
        }
    }

    @Test
    fun resolvePaths() {
        assertEquals("OEBPS/Images/a b.jpg", EpubBook.resolve("OEBPS/Text", "../Images/a%20b.jpg#x"))
        assertEquals("ch1.xhtml", EpubBook.resolve("", "./ch1.xhtml"))
        assertEquals("a+b.html", EpubBook.resolve("", "a+b.html"))
        assertEquals("note", EpubBook.fragmentOf("ch1.xhtml#note"))
    }

    @Test
    fun styleIsInjectedBeforeHeadAndKeepsUtf8() {
        val html = "<html><head><title>标题</title></head><body><p>中文内容</p></body></html>".toByteArray()
        val tag = Paginator.styleTag(Paginator.Style(), 400, 600)
        val out = String(Paginator.injectStyle(html, tag), Charsets.UTF_8)
        assertTrue(out.contains("__einkcss"))
        assertTrue(out.indexOf("__einkcss") < out.indexOf("</head>"))
        assertTrue(out.contains("中文内容"))
        assertTrue(!Paginator.css(Paginator.Style(), 400, 600).contains("<"))
        assertTrue(!Paginator.css(Paginator.Style(), 400, 600).contains("&"))
    }

    @Test
    fun baseUrlNormalisation() {
        assertEquals("http://192.168.1.2:5000", KavitaApi.normalizeBase("192.168.1.2:5000/"))
        assertEquals("https://k.example.com/kavita", KavitaApi.normalizeBase("https://k.example.com/kavita/api/opds/abc"))
        assertEquals("abc123_X", KavitaApi.keyFromOpdsUrl("http://h:5000/api/opds/abc123_X"))
        assertEquals("http://h:5000", KavitaApi.normalizeBase("http://h:5000/api"))
    }
}

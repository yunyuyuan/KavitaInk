package com.manus.kavitaink.core

import com.manus.kavitaink.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 暂停 / 续传 / 网络异常联调测试。需要真实 Kavita + tools/throttle_proxy.py：
 *   KAVITA_PROXY_URL=http://localhost:5001 KAVITA_USER=admin KAVITA_PASS=xxx \
 *   BIG_EPUB_PATH=... ./gradlew testDebugUnitTest --tests '*PauseResumeLiveTest*'
 * 服务器上需要有 “Big Novel”（约 15 MB 的 EPUB）和 “Big Manga”（80 页漫画）。
 */
class PauseResumeLiveTest {
    private fun env(k: String) = System.getenv(k).orEmpty()
    private val ctrl = File(System.getenv("PROXY_CTRL") ?: "/tmp/proxy.json")
    private lateinit var api: KavitaApi

    private fun proxy(mode: String, rate: Int = 400_000) {
        ctrl.writeText("{\"mode\":\"$mode\",\"rate\":$rate}")
        Thread.sleep(700) // 代理每 0.3 秒读取一次
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun task(name: String): DownloadTask {
        val s = api.search(name).first { it.name == name }
        val c = api.chapters(s.id, s.format).first()
        return DownloadTask.of(api.serverKey, s.name, s.format, c)
    }

    /** 在后台线程下载，返回 (线程, 结果容器) */
    private class Run(val dl: Downloader) {
        @Volatile var stop = false
        @Volatile var error: Throwable? = null
        @Volatile var done = 0L
        @Volatile var total = 0L
        val firstReport = ArrayList<Long>()
        lateinit var thread: Thread
    }

    private fun start(storage: Storage, t: DownloadTask): Run {
        val r = Run(Downloader(api, storage))
        r.thread = Thread {
            try {
                r.dl.download(t, { d, tot, _ ->
                    synchronized(r.firstReport) { if (r.firstReport.isEmpty()) r.firstReport += d }
                    r.done = d
                    r.total = tot
                }, { r.stop })
            } catch (e: Throwable) {
                r.error = e
            }
        }.also { it.start() }
        return r
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("等待超时")
            Thread.sleep(100)
        }
    }

    /** 模拟用户点“暂停”：置标志 + 断开连接；返回下载线程退出所用的毫秒数 */
    private fun pause(r: Run): Long {
        val t0 = System.currentTimeMillis()
        r.stop = true
        r.dl.abort()
        r.thread.join(10_000)
        return System.currentTimeMillis() - t0
    }

    @Before
    fun setUp() {
        val url = env("KAVITA_PROXY_URL")
        assumeTrue("未配置 KAVITA_PROXY_URL，跳过", url.isNotBlank())
        proxy("ok", 400_000)
        api = KavitaApi.login(url, env("KAVITA_USER"), env("KAVITA_PASS"))
    }

    @Test
    fun epubPauseThenResumeFromBreakpoint() {
        val storage = Storage(Files.createTempDirectory("kpause").toFile())
        val t = task("Big Novel")
        proxy("ok", 400_000)
        val r = start(storage, t)
        waitFor(60_000) { r.done >= 2_500_000 }
        val ms = pause(r)
        println("EPUB 暂停耗时 ${ms}ms，已下载 ${r.done} / ${r.total}")
        assertTrue("暂停应在 1.5 秒内生效，实际 ${ms}ms", ms < 1500)
        assertTrue("应以 Cancelled 结束：${r.error}", r.error is Downloader.Cancelled)
        val dir = storage.chapterDir(t.serverKey, t.seriesId, t.chapterId)
        val part = File(dir, Storage.EPUB_FILE + ".part")
        assertTrue("未完成文件应保留", part.exists())
        val kept = part.length()
        assertTrue("已下载部分 $kept", kept in 2_000_000 until t.bytes)
        assertFalse(File(dir, Storage.EPUB_FILE).exists())
        assertFalse(storage.isComplete(t.serverKey, t.seriesId, t.chapterId))
        // 磁盘进度估算（暂停项 / 重启后显示）
        val est = Downloader.partialProgress(storage, t)
        assertNotNull(est)
        assertEquals(kept, est!!.first)
        assertEquals(t.bytes, est.second)

        // 继续：应从断点开始，并以 Range 请求续传
        proxy("ok", 6_000_000)
        val r2 = start(storage, t)
        r2.thread.join(120_000)
        assertEquals(null, r2.error)
        println("续传首次进度 = ${r2.firstReport}，断点 = $kept")
        assertEquals("续传应从断点开始", kept, r2.firstReport.first())
        val local = storage.readChapter(dir)!!
        assertEquals(t.bytes, local.epubFile.length())
        val origin = env("BIG_EPUB_PATH")
        if (origin.isNotBlank()) assertEquals("与服务器原文件逐字节一致", sha(File(origin)), sha(local.epubFile))
        EpubBook(local.epubFile).use { assertEquals(12, it.spine.size) }
        val log = File("/tmp/proxy.log").readText()
        assertTrue("代理日志中应有 Range: bytes=$kept-", log.contains("chapterId=${t.chapterId} range=$kept-"))
        storage.root.deleteRecursively()
    }

    @Test
    fun mangaPauseKeepsPagesAndResumeSkipsThem() {
        val storage = Storage(Files.createTempDirectory("kpause").toFile())
        val t = task("Big Manga")
        assertEquals(80, t.pages)
        proxy("ok", 1_500_000)
        val r = start(storage, t)
        waitFor(120_000) { r.done >= 8 }
        val ms = pause(r)
        assertTrue("暂停应在 1.5 秒内生效，实际 ${ms}ms", ms < 1500)
        assertTrue(r.error is Downloader.Cancelled)
        val pagesDir = File(storage.chapterDir(t.serverKey, t.seriesId, t.chapterId), Storage.PAGES_DIR)
        val kept = pagesDir.listFiles()!!.filter { !it.name.endsWith(".part") }
        println("漫画暂停耗时 ${ms}ms，保留 ${kept.size} 页")
        assertTrue(kept.size in 8 until 80)
        val mtimes = kept.associate { it.name to it.lastModified() }
        val est = Downloader.partialProgress(storage, t)!!
        assertEquals(kept.size.toLong(), est.first)
        assertEquals(80L, est.second)
        assertTrue(est.third)

        Thread.sleep(1100) // 确保若被重新下载，修改时间一定不同
        proxy("ok", 20_000_000)
        val r2 = start(storage, t)
        r2.thread.join(300_000)
        assertEquals(null, r2.error)
        assertEquals("续传首次进度应等于已有页数", kept.size.toLong(), r2.firstReport.first())
        val all = pagesDir.listFiles()!!.filter { !it.name.endsWith(".part") }
        assertEquals(80, all.size)
        for ((name, m) in mtimes) assertEquals("第 $name 页不应被重新下载", m, File(pagesDir, name).lastModified())
        assertTrue(storage.isComplete(t.serverKey, t.seriesId, t.chapterId))
        storage.root.deleteRecursively()
    }

    @Test
    fun pauseWorksImmediatelyEvenWhenNetworkStalls() {
        val storage = Storage(Files.createTempDirectory("kpause").toFile())
        val t = task("Big Novel")
        proxy("ok", 400_000)
        val r = start(storage, t)
        waitFor(60_000) { r.done >= 500_000 }
        proxy("stall")
        val before = r.done
        Thread.sleep(3000)
        assertTrue("网络卡死后不应再有进展", r.done - before < 300_000)
        assertTrue("下载线程此时卡在读取上", r.thread.isAlive)
        r.stop = true
        val t0 = System.currentTimeMillis()
        r.dl.abort()
        r.thread.join(70_000)
        val ms = System.currentTimeMillis() - t0
        // 注意：桌面 JDK 的 HttpURLConnection 无法从其他线程打断阻塞中的读取，只能等读取超时（60 秒）；
        // Android 的实现（OkHttp）disconnect() 会直接关闭 socket，立即生效——这一点在模拟器上实测（tools/e2e_pause.py）。
        println("网络卡死时暂停耗时 ${ms}ms（JVM；Android 上见模拟器测试）")
        assertFalse(r.thread.isAlive)
        assertTrue("网络卡死后暂停，结果应是“已停止”而不是失败：${r.error}", r.error is Downloader.Cancelled)
        proxy("ok", 400_000)
        storage.root.deleteRecursively()
    }

    @Test
    fun networkDropIsTransientAndKeepsPartialData() {
        val storage = Storage(Files.createTempDirectory("kpause").toFile())
        val t = task("Big Novel")
        proxy("ok", 400_000)
        val r = start(storage, t)
        waitFor(60_000) { r.done >= 1_000_000 }
        proxy("drop")
        r.thread.join(30_000)
        val err = r.error
        println("断网后的异常：${err?.javaClass?.simpleName}: ${err?.message} -> ${KavitaApi.friendlyError(err, XmlTexts("zh"))}")
        assertTrue("断网应抛出 IO 异常而不是被当作完成：$err", err is IOException)
        assertTrue("应判定为可自动重试", KavitaApi.isTransient(err))
        assertFalse(storage.isComplete(t.serverKey, t.seriesId, t.chapterId))
        val part = File(storage.chapterDir(t.serverKey, t.seriesId, t.chapterId), Storage.EPUB_FILE + ".part")
        assertTrue("断网后已下载部分应保留：${part.length()}", part.length() >= 900_000)
        // 断网期间重试仍然失败，且同样可重试
        val r1 = start(storage, t)
        r1.thread.join(30_000)
        assertTrue(KavitaApi.isTransient(r1.error))
        // 网络恢复后续传完成
        proxy("ok", 6_000_000)
        val r2 = start(storage, t)
        r2.thread.join(120_000)
        assertEquals(null, r2.error)
        val f = storage.readChapter(storage.chapterDir(t.serverKey, t.seriesId, t.chapterId))!!.epubFile
        assertEquals(t.bytes, f.length())
        val origin = env("BIG_EPUB_PATH")
        if (origin.isNotBlank()) assertEquals(sha(File(origin)), sha(f))
        // 不可重试的错误
        assertFalse(KavitaApi.isTransient(ApiException(401, R.string.err_http_401)))
        assertFalse(KavitaApi.isTransient(ApiException(403, R.string.err_http_403)))
        assertFalse(KavitaApi.isTransient(StorageException(R.string.err_no_space)))
        assertTrue(KavitaApi.isTransient(ApiException(503, R.string.err_http_5xx, "", 503)))
        storage.root.deleteRecursively()
    }
}

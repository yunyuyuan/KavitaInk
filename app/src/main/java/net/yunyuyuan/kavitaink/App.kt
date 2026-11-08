package net.yunyuyuan.kavitaink

import android.app.Application
import net.yunyuyuan.kavitaink.core.FontStore
import net.yunyuyuan.kavitaink.core.KavitaApi
import net.yunyuyuan.kavitaink.core.Storage
import net.yunyuyuan.kavitaink.download.DownloadQueue
import java.io.File

class App : Application() {

    companion object {
        lateinit var I: App
            private set
    }

    lateinit var prefs: Prefs
        private set
    lateinit var storage: Storage
        private set
    lateinit var queue: DownloadQueue
        private set
    /** 用户导入的小说字体 */
    val fonts: FontStore by lazy { FontStore(File(filesDir, "fonts")) }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        I = this
        prefs = Prefs(this)
        storage = Storage(indexDir(), contentDir())
        queue = DownloadQueue(this)
    }

    /** 私有索引（下载记录、封面、逐页下载的图片）；1.2.0 及以前的 library 目录不再使用，也不会删除 */
    private fun indexDir(): File {
        val base = runCatching { getExternalFilesDir(null) }.getOrNull() ?: filesDir
        return File(base, "index").also { it.mkdirs() }
    }

    /** 公共下载目录：内部存储/Books/KavitaInk，其他阅读器也能打开 */
    @Suppress("DEPRECATION")
    private fun contentDir(): File = File(File(android.os.Environment.getExternalStorageDirectory(), "Books"), "KavitaInk")

    fun api(): KavitaApi? = prefs.api()

    val coverCacheDir: File get() = File(cacheDir, "covers")
}

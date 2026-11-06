package com.manus.kavitaink

import android.app.Application
import com.manus.kavitaink.core.KavitaApi
import com.manus.kavitaink.core.Storage
import com.manus.kavitaink.download.DownloadQueue
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

    override fun onCreate() {
        super.onCreate()
        I = this
        prefs = Prefs(this)
        storage = Storage(rootDir())
        queue = DownloadQueue(this)
    }

    /** 下载目录只在首次启动时决定一次，之后固定，避免存储卡插拔导致“书没了” */
    private fun rootDir(): File {
        val saved = prefs.rootPath
        if (saved.isNotBlank()) return File(saved)
        val base = runCatching { getExternalFilesDir(null) }.getOrNull() ?: filesDir
        val dir = File(base, "library")
        dir.mkdirs()
        prefs.rootPath = dir.absolutePath
        return dir
    }

    fun api(): KavitaApi? = prefs.api()

    val coverCacheDir: File get() = File(cacheDir, "covers")
}

package com.manus.kavitaink.core

/**
 * 下载时用到的书库信息（书库名、根目录）和系列所属书库。
 * 同一次下载会话里只请求一次书库列表；每个系列只查一次所属书库。线程安全，可被多个下载线程共用。
 */
class LibraryCache(private val api: KavitaApi) {
    private var libs: Map<Int, Library>? = null
    private val seriesLib = HashMap<Int, Int>()

    /** 返回系列所在的书库；网络类错误直接抛出（由调用方重试），其他错误（如无权限）返回 null */
    fun libraryOf(seriesId: Int): Library? {
        val libId = synchronized(this) { seriesLib[seriesId] } ?: guard { api.seriesInfo(seriesId).libraryId }?.also {
            synchronized(this) { seriesLib[seriesId] = it }
        } ?: return null
        val all = synchronized(this) { libs } ?: guard { api.libraries().associateBy { it.id } }?.also {
            synchronized(this) { libs = it }
        } ?: return null
        return all[libId]
    }

    private fun <T> guard(fn: () -> T): T? = try {
        fn()
    } catch (t: Throwable) {
        if (KavitaApi.isTransient(t)) throw t
        null
    }
}

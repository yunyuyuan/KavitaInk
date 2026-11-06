package com.manus.kavitaink

import android.content.Context
import com.manus.kavitaink.core.KavitaApi
import com.manus.kavitaink.core.LocalChapter
import com.manus.kavitaink.core.Paginator

/** 所有设置与本地阅读进度（只存在本机，不与服务器同步） */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pr = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    // ---------- 账户 ----------
    var serverUrl: String get() = str("serverUrl"); set(v) = putStr("serverUrl", v)
    var apiKey: String get() = str("apiKey"); set(v) = putStr("apiKey", v)
    var username: String get() = str("username"); set(v) = putStr("username", v)
    var rootPath: String get() = str("rootPath"); set(v) = putStr("rootPath", v)

    fun api(): KavitaApi? =
        if (serverUrl.isBlank() || apiKey.isBlank()) null else KavitaApi(serverUrl, apiKey)

    // ---------- 翻页 ----------
    var volumeKeys: Boolean get() = bool("volumeKeys", true); set(v) = putBool("volumeKeys", v)
    var swapVolume: Boolean get() = bool("swapVolume", false); set(v) = putBool("swapVolume", v)
    /** 0 = 左右三分（中间呼出菜单），1 = 左右两分（长按呼出菜单） */
    var tapMode: Int get() = int("tapMode", 0); set(v) = putInt("tapMode", v)
    var swipe: Boolean get() = bool("swipe", true); set(v) = putBool("swipe", v)

    // ---------- 漫画 / PDF ----------
    var mangaRtl: Boolean get() = bool("mangaRtl", false); set(v) = putBool("mangaRtl", v)
    /** 0 = 适应屏幕，1 = 适应宽度（长图分段翻页） */
    var fitMode: Int get() = int("fitMode", 0); set(v) = putInt("fitMode", v)
    var contrast: Boolean get() = bool("contrast", false); set(v) = putBool("contrast", v)
    var showPageNum: Boolean get() = bool("showPageNum", true); set(v) = putBool("showPageNum", v)

    // ---------- 小说 ----------
    var fontZoom: Int get() = int("fontZoom", 100); set(v) = putInt("fontZoom", v)
    var lineHeight: Int get() = int("lineHeight", 160); set(v) = putInt("lineHeight", v)
    var margin: Int get() = int("margin", 16); set(v) = putInt("margin", v)
    var fontFamily: Int get() = int("fontFamily", 0); set(v) = putInt("fontFamily", v)
    var forceBlack: Boolean get() = bool("forceBlack", true); set(v) = putBool("forceBlack", v)

    // ---------- 墨水屏 ----------
    /** 每翻 N 页做一次黑白闪屏全刷，0 = 关闭 */
    var fullRefreshEvery: Int get() = int("fullRefreshEvery", 0); set(v) = putInt("fullRefreshEvery", v)
    var showCovers: Boolean get() = bool("showCovers", true); set(v) = putBool("showCovers", v)
    var notifAsked: Boolean get() = bool("notifAsked", false); set(v) = putBool("notifAsked", v)

    fun paginatorStyle() = Paginator.Style(
        marginX = margin,
        marginTop = 18,
        marginBottom = if (showPageNum) 30 else 18,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        forceBlack = forceBlack,
    )

    // ---------- 阅读进度（仅本地） ----------
    fun position(c: LocalChapter): String = pr.getString("pos:${c.serverKey}:${c.chapterId}", "") ?: ""

    fun percent(serverKey: String, chapterId: Int): Int = pr.getInt("pct:$serverKey:$chapterId", -1)

    fun savePosition(c: LocalChapter, pos: String, pct: Int) {
        pr.edit()
            .putString("pos:${c.serverKey}:${c.chapterId}", pos)
            .putInt("pct:${c.serverKey}:${c.chapterId}", pct.coerceIn(0, 100))
            .putLong("last:${c.serverKey}:${c.seriesId}", System.currentTimeMillis())
            .putInt("lastch:${c.serverKey}:${c.seriesId}", c.chapterId)
            .apply()
    }

    fun lastRead(serverKey: String, seriesId: Int): Long = pr.getLong("last:$serverKey:$seriesId", 0)

    fun lastChapter(serverKey: String, seriesId: Int): Int = pr.getInt("lastch:$serverKey:$seriesId", -1)

    fun clearProgress(serverKey: String, chapterId: Int) {
        pr.edit().remove("pos:$serverKey:$chapterId").remove("pct:$serverKey:$chapterId").apply()
    }
}

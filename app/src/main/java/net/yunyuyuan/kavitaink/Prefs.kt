package net.yunyuyuan.kavitaink

import android.content.Context
import net.yunyuyuan.kavitaink.core.KavitaApi
import net.yunyuyuan.kavitaink.core.LocalChapter

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

    init {
        migrate()
    }

    /**
     * 1.1.0 → 1.2.0：1.1.0 在小说和漫画里都显示页码（「显示页码」开关），1.2.0 改为可配置的状态栏，
     * 新装用户漫画状态栏默认关闭。为了让老用户升级后阅读界面不变：漫画状态栏保持开启且只显示页码，
     * 关掉过页码的用户两边都关闭。
     */
    private fun migrate() {
        if (sp.getInt("schema", 0) >= 2) return
        val e = sp.edit()
        val existing = sp.contains("rootPath") || sp.contains("serverUrl")
        if (existing) {
            val pageNum = sp.getBoolean("showPageNum", true)
            e.putBoolean("sbNovelOn", pageNum)
            e.putBoolean("sbMangaOn", pageNum)
            e.putInt("sbMangaItems", SB_PAGE)
            // 1.1.0 的字体选项：0 默认 / 1 衬线 / 2 无衬线
            if (!sp.contains("fontId")) e.putString("fontId", legacyFont(sp.getInt("fontFamily", 0)))
        }
        e.putInt("schema", 2).apply()
    }

    // ---------- 账户 ----------
    var serverUrl: String get() = str("serverUrl"); set(v) = putStr("serverUrl", v)
    var apiKey: String get() = str("apiKey"); set(v) = putStr("apiKey", v)
    var username: String get() = str("username"); set(v) = putStr("username", v)
    var rootPath: String get() = str("rootPath"); set(v) = putStr("rootPath", v)

    fun api(): KavitaApi? =
        if (serverUrl.isBlank() || apiKey.isBlank()) null else KavitaApi(serverUrl, apiKey)

    // ---------- 通用 ----------
    /** 0 = 跟随系统，1 = 竖屏，2 = 横屏，3 = 反向竖屏，4 = 反向横屏 */
    var orientation: Int get() = int("orientation", 0); set(v) = putInt("orientation", v)

    // ---------- 翻页 ----------
    var volumeKeys: Boolean get() = bool("volumeKeys", true); set(v) = putBool("volumeKeys", v)
    var swapVolume: Boolean get() = bool("swapVolume", false); set(v) = putBool("swapVolume", v)
    /** 0 = 左右三分（中间呼出菜单），1 = 左右两分（长按呼出菜单） */
    var tapMode: Int get() = int("tapMode", 0); set(v) = putInt("tapMode", v)
    var swipe: Boolean get() = bool("swipe", true); set(v) = putBool("swipe", v)
    /** 同时下载的章节数；逐页下载时也是一章内同时下载的页数（1–4，默认 2） */
    var downloadConcurrency: Int
        get() = int("downloadConcurrency", 2).coerceIn(1, MAX_CONCURRENCY)
        set(v) = putInt("downloadConcurrency", v.coerceIn(1, MAX_CONCURRENCY))

    // ---------- 漫画 / PDF ----------
    var mangaRtl: Boolean get() = bool("mangaRtl", false); set(v) = putBool("mangaRtl", v)
    /** 0 = 适应屏幕，1 = 适应宽度（长图分段翻页） */
    var fitMode: Int get() = int("fitMode", 0); set(v) = putInt("fitMode", v)
    var contrast: Boolean get() = bool("contrast", false); set(v) = putBool("contrast", v)
    /** 双页模式：0 = 关闭，1 = 横屏时自动，2 = 始终开启 */
    var spreadMode: Int get() = int("spreadMode", 0); set(v) = putInt("spreadMode", v)
    /** 页序：0 = 跟随阅读方向，1 = 左→右，2 = 右→左 */
    var spreadOrder: Int get() = int("spreadOrder", 0); set(v) = putInt("spreadOrder", v)
    /** 中缝间距（px），0–64，每档 4 */
    var spreadGap: Int get() = int("spreadGap", 8); set(v) = putInt("spreadGap", v)
    var spreadDivider: Boolean get() = bool("spreadDivider", false); set(v) = putBool("spreadDivider", v)
    /** 分隔线粗细（px），1–8 */
    var dividerWidth: Int get() = int("dividerWidth", 2); set(v) = putInt("dividerWidth", v)
    /** 分隔线颜色：0 黑，1 深灰，2 浅灰，3 白 */
    var dividerColor: Int get() = int("dividerColor", 0); set(v) = putInt("dividerColor", v)
    /** 双页时首页（封面）单独显示 */
    var spreadCover: Boolean get() = bool("spreadCover", true); set(v) = putBool("spreadCover", v)
    var cropMargins: Boolean get() = bool("cropMargins", false); set(v) = putBool("cropMargins", v)
    /** 拆分跨页：竖屏时把宽页拆成左右两半分别显示（默认关） */
    var splitWide: Boolean get() = bool("splitWide", false); set(v) = putBool("splitWide", v)
    /** 裁白边时同时裁黑边 */
    var cropBlack: Boolean get() = bool("cropBlack", false); set(v) = putBool("cropBlack", v)

    /** 「错开一页」：手动修正跨页没对齐，按章节记住 */
    fun spreadShift(c: LocalChapter): Boolean = pr.getBoolean("shift:${c.serverKey}:${c.chapterId}", false)
    fun setSpreadShift(c: LocalChapter, v: Boolean) = pr.edit().putBoolean("shift:${c.serverKey}:${c.chapterId}", v).apply()

    // ---------- 小说 ----------
    var fontZoom: Int get() = int("fontZoom", 100); set(v) = putInt("fontZoom", v)
    var lineHeight: Int get() = int("lineHeight", 160); set(v) = putInt("lineHeight", v)
    /** 左右边距（px，按 CSS 像素）；沿用 1.1.0 的 margin 键 */
    var marginX: Int get() = int("margin", 16); set(v) = putInt("margin", v)
    /** 上下边距；1.1.0 固定为 18 */
    var marginY: Int get() = int("marginY", 18); set(v) = putInt("marginY", v)
    /** 字体：builtin:default / builtin:serif / builtin:sans，或已安装字体的 id */
    var fontId: String get() = str("fontId", FONT_DEFAULT); set(v) = putStr("fontId", v)
    var forceBlack: Boolean get() = bool("forceBlack", true); set(v) = putBool("forceBlack", v)
    /** 使用书籍自带排版：开启时首行缩进 / 段间距 / 对齐 / 字间距都不覆盖书里的样式 */
    var bookStyle: Boolean get() = bool("bookStyle", true); set(v) = putBool("bookStyle", v)
    /** 首行缩进，单位：字（em），0–4 */
    var textIndent: Int get() = int("textIndent", 2); set(v) = putInt("textIndent", v)
    /** 段间距，单位：0.1 行，0 / 5 / 10 / 15 */
    var paraSpacing: Int get() = int("paraSpacing", 5); set(v) = putInt("paraSpacing", v)
    var justify: Boolean get() = bool("justify", true); set(v) = putBool("justify", v)
    /** 字间距，单位：0.01 em，0 / 5 / 10 / 15 */
    var letterSpacing: Int get() = int("letterSpacing", 0); set(v) = putInt("letterSpacing", v)

    // ---------- 阅读状态栏 ----------
    var sbNovelOn: Boolean get() = bool("sbNovelOn", true); set(v) = putBool("sbNovelOn", v)
    var sbNovelItems: Int get() = int("sbNovelItems", SB_DEFAULT); set(v) = putInt("sbNovelItems", v)
    var sbNovelTop: Boolean get() = bool("sbNovelTop", false); set(v) = putBool("sbNovelTop", v)
    /** 0 = 小，1 = 中 */
    var sbNovelSize: Int get() = int("sbNovelSize", 0); set(v) = putInt("sbNovelSize", v)
    var sbMangaOn: Boolean get() = bool("sbMangaOn", false); set(v) = putBool("sbMangaOn", v)
    var sbMangaItems: Int get() = int("sbMangaItems", SB_DEFAULT); set(v) = putInt("sbMangaItems", v)
    var sbMangaTop: Boolean get() = bool("sbMangaTop", false); set(v) = putBool("sbMangaTop", v)
    var sbMangaSize: Int get() = int("sbMangaSize", 0); set(v) = putInt("sbMangaSize", v)

    /** 读取 / 修改某一类阅读器的状态栏设置 */
    inner class StatusBarPrefs(private val novel: Boolean) {
        var on: Boolean
            get() = if (novel) sbNovelOn else sbMangaOn
            set(v) { if (novel) sbNovelOn = v else sbMangaOn = v }
        var items: Int
            get() = if (novel) sbNovelItems else sbMangaItems
            set(v) { if (novel) sbNovelItems = v else sbMangaItems = v }
        var top: Boolean
            get() = if (novel) sbNovelTop else sbMangaTop
            set(v) { if (novel) sbNovelTop = v else sbMangaTop = v }
        var size: Int
            get() = if (novel) sbNovelSize else sbMangaSize
            set(v) { if (novel) sbNovelSize = v else sbMangaSize = v }
    }

    fun statusBar(novel: Boolean) = StatusBarPrefs(novel)

    // ---------- 墨水屏 ----------
    /** 每翻 N 页做一次黑白闪屏全刷，0 = 关闭 */
    var fullRefreshEvery: Int get() = int("fullRefreshEvery", 0); set(v) = putInt("fullRefreshEvery", v)
    var showCovers: Boolean get() = bool("showCovers", true); set(v) = putBool("showCovers", v)
    var notifAsked: Boolean get() = bool("notifAsked", false); set(v) = putBool("notifAsked", v)

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

    companion object {
        const val MAX_CONCURRENCY = 4
        const val SB_PAGE = 1
        const val SB_PERCENT = 2
        const val SB_CHAPTER = 4
        const val SB_TIME = 8
        const val SB_BATTERY = 16
        const val SB_DEFAULT = SB_PAGE or SB_TIME or SB_BATTERY

        const val FONT_DEFAULT = "builtin:default"
        const val FONT_SERIF = "builtin:serif"
        const val FONT_SANS = "builtin:sans"

        fun legacyFont(i: Int) = when (i) {
            1 -> FONT_SERIF
            2 -> FONT_SANS
            else -> FONT_DEFAULT
        }
    }
}

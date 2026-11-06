package com.manus.kavitaink

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.manus.kavitaink.core.Texts
import java.util.Locale

/** 用 Resources 实现的 [Texts]，跟随 Context 的当前语言 */
class ResTexts(private val res: Resources) : Texts {
    override fun s(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

    override fun p(id: Int, n: Int, vararg args: Any?): String =
        if (args.isEmpty()) res.getQuantityString(id, n, n) else res.getQuantityString(id, n, *args)
}

fun Context.texts(): Texts = ResTexts(resources)

/**
 * 应用内语言设置。
 *  - Android 13+：以系统的「应用语言」（LocaleManager）为准，应用内的设置也写到那里，两边始终一致；
 *  - Android 12 及以下：设置保存在 SharedPreferences，在 attachBaseContext 里用 Configuration.setLocale 包装 Context。
 * 选「跟随系统」且系统语言不受支持时，自然回退到英文（values/ 即英文）。
 */
object Lang {
    /** tag 为 BCP-47；name 用该语言自己的文字显示 */
    data class Option(val tag: String, val name: String)

    val OPTIONS = listOf(
        Option("en", "English"),
        Option("zh-CN", "简体中文"),
        Option("zh-TW", "繁體中文"),
        Option("ja", "日本語"),
        Option("ko", "한국어"),
        Option("es", "Español"),
        Option("fr", "Français"),
        Option("de", "Deutsch"),
        Option("ru", "Русский"),
        Option("pt-BR", "Português (Brasil)"),
    )

    private const val PREFS = "settings"
    private const val KEY = "language"

    private val usesSystemPerAppLanguage get() = Build.VERSION.SDK_INT >= 33

    fun localeOf(tag: String): Locale {
        val p = tag.split('-')
        return if (p.size >= 2) Locale(p[0], p[1]) else Locale(p[0])
    }

    /** 当前设置，"" = 跟随系统 */
    fun current(ctx: Context): String {
        if (usesSystemPerAppLanguage) {
            val lm = ctx.getSystemService(LocaleManager::class.java) ?: return ""
            val loc = lm.applicationLocales.takeIf { !it.isEmpty }?.get(0) ?: return ""
            // 系统里选的可能是 zh-Hant-HK 之类，对应到我们的选项
            return OPTIONS.firstOrNull { matches(loc, localeOf(it.tag)) }?.tag ?: loc.toLanguageTag()
        }
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    }

    private fun matches(a: Locale, b: Locale): Boolean {
        if (a.language != b.language) return false
        if (b.country.isEmpty()) return true
        if (a.country == b.country) return true
        // 繁体：zh-HK / zh-MO / zh-Hant 都算 zh-TW
        return a.language == "zh" && b.country == "TW" && (a.script == "Hant" || a.country in setOf("HK", "MO"))
    }

    /** 切换语言并立即生效 */
    fun set(activity: Activity, tag: String) {
        if (usesSystemPerAppLanguage) {
            val lm = activity.getSystemService(LocaleManager::class.java) ?: return
            // 系统会自动重建所有界面
            lm.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        activity.recreate()
    }

    @Suppress("DEPRECATION")
    private fun systemLocale(): Locale =
        if (Build.VERSION.SDK_INT >= 24) Resources.getSystem().configuration.locales[0]
        else Resources.getSystem().configuration.locale

    /** 在 Activity / Service / Application 的 attachBaseContext 中调用 */
    fun wrap(base: Context): Context {
        if (usesSystemPerAppLanguage) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        val sys = systemLocale()
        // Android 5 / 6 的资源匹配不认文字脚本，香港 / 澳门繁体会落到简体（values-zh），这里改用台湾繁体
        val hantFallback = tag.isEmpty() && Build.VERSION.SDK_INT < 24 &&
            sys.language == "zh" && sys.country in setOf("HK", "MO")
        if (tag.isEmpty() && !hantFallback) {
            Locale.setDefault(sys)
            return base
        }
        val loc = if (hantFallback) Locale("zh", "TW") else localeOf(tag)
        // 让 String.format、日期等也按所选语言格式化
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        if (Build.VERSION.SDK_INT >= 24) cfg.setLocales(LocaleList(loc))
        return base.createConfigurationContext(cfg)
    }
}

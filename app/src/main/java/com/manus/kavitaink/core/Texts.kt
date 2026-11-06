package com.manus.kavitaink.core

import com.manus.kavitaink.R
import org.json.JSONObject
import java.io.IOException

/**
 * 取文案的最小接口。core 层不直接依赖 Context：
 *  - App 里由 Resources 实现（跟随当前语言）；
 *  - JVM 单元测试里由解析 res/values-xx/strings*.xml 的实现代替，可以按语言断言。
 */
interface Texts {
    fun s(id: Int, vararg args: Any?): String

    /** 复数文案；不传 args 时默认把 n 作为唯一参数 */
    fun p(id: Int, n: Int, vararg args: Any?): String
}

/** 携带可翻译提示的异常：抛出时只记录文案 id 和参数，显示时才按当前语言格式化 */
interface Localized {
    val res: Int
    val args: Array<out Any?>
}

open class LocalizedIOException(override val res: Int, vararg a: Any?) : IOException("res#$res ${a.joinToString()}"), Localized {
    override val args: Array<out Any?> = a
}

/**
 * 服务器返回的错误。res 为提示文案，detail 为服务器给出的原文（不翻译）。
 * res = 0 时直接显示 detail。
 */
class ApiException(val code: Int, res: Int, val detail: String = "", vararg a: Any?) : LocalizedIOException(res, *a)

class StorageException(res: Int, vararg a: Any?) : LocalizedIOException(res, *a)

/** 生成章节标题所需的原始信息（来自 Kavita 的卷 / 章节数据） */
data class LabelSrc(
    val volName: String,
    val range: String,
    val titleName: String,
    val title: String,
    val bookish: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", volName).put("r", range).put("tn", titleName).put("t", title).put("b", bookish)

    companion object {
        fun fromJson(o: JSONObject?): LabelSrc? = o?.let {
            LabelSrc(it.s("v"), it.s("r"), it.s("tn"), it.s("t"), it.optBoolean("b"))
        }
    }
}

/**
 * 章节标题。group: 0 = 普通卷, 1 = 无卷章节, 2 = 特别篇/单本。
 * 标题在显示时按当前语言生成；1.1.0 下载的旧数据只有中文标题，用 [legacy] 识别后同样能转换。
 */
object Labels {
    const val DEFAULT_RANGE = "-100000"

    fun render(group: Int, src: LabelSrc?, stored: String, x: Texts): String =
        when {
            src != null -> of(group, src, x)
            else -> legacy(stored, x) ?: stored
        }

    fun of(group: Int, s: LabelSrc, x: Texts): String {
        val range = s.range
        val noRange = range.isBlank() || range == DEFAULT_RANGE
        return when (group) {
            2 -> listOf(s.title, s.titleName, range).firstOrNull { it.isNotBlank() && it != DEFAULT_RANGE }
                ?: x.s(R.string.label_special)
            1 -> if (noRange) {
                listOf(s.title, s.titleName).firstOrNull { it.isNotBlank() && it != DEFAULT_RANGE }
                    ?: x.s(R.string.label_chapter_generic)
            } else {
                chapter(range, s.bookish, x) + suffix(s.titleName, range)
            }
            else -> {
                val vol = volume(s.volName, x)
                if (noRange) vol + suffix(s.titleName, range)
                else x.s(R.string.label_vol_chapter, vol, chapter(range, s.bookish, x)) + suffix(s.titleName, range)
            }
        }
    }

    fun volume(name: String, x: Texts): String = when {
        name.isBlank() -> x.s(R.string.label_single_volume)
        isNumber(name) -> x.s(R.string.label_volume_n, name)
        else -> name
    }

    fun chapter(range: String, bookish: Boolean, x: Texts): String = when {
        !isNumber(range) -> range
        bookish -> x.s(R.string.label_chapter_book_n, range)
        else -> x.s(R.string.label_chapter_manga_n, range)
    }

    private fun suffix(titleName: String, range: String): String =
        if (titleName.isBlank() || titleName == range || titleName == DEFAULT_RANGE) "" else " · $titleName"

    private fun isNumber(s: String) = s.isNotBlank() && s.all { it.isDigit() || it == '.' || it == '-' }

    private val legacyVol = Regex("^第(.+?)卷(?: 第(.+?)([话章]))?( · .*)?$")
    private val legacyCh = Regex("^第(.+?)([话章])( · .*)?$")

    /** 识别 1.1.0 生成的中文标题（如「第1卷 第3话 · 标题」），转换成当前语言；不是这种格式时返回 null */
    fun legacy(label: String, x: Texts): String? {
        when (label) {
            "特别篇" -> return x.s(R.string.label_special)
            "章节" -> return x.s(R.string.label_chapter_generic)
            "单卷" -> return x.s(R.string.label_single_volume)
        }
        legacyVol.matchEntire(label)?.let { m ->
            val (vol, ch, unit, tail) = m.destructured
            if (!isNumber(vol)) return null
            val v = x.s(R.string.label_volume_n, vol)
            val body = if (ch.isEmpty()) v else x.s(R.string.label_vol_chapter, v, chapter(ch, unit == "章", x))
            return body + tail
        }
        legacyCh.matchEntire(label)?.let { m ->
            val (ch, unit, tail) = m.destructured
            if (!isNumber(ch)) return null
            return chapter(ch, unit == "章", x) + tail
        }
        return null
    }
}

/** 把异常转换成当前语言的提示 */
fun Texts.error(t: Throwable?): String = KavitaApi.friendlyError(t, this)

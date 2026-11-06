package com.manus.kavitaink.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM 单元测试用的 Texts：直接解析 src/main/res/values{-xx}/strings*.xml，
 * 通过 R 类反射把资源 id 对应到键名。缺失的键回退到 values/（和 Android 一样）。
 */
class XmlTexts(val qualifier: String = "") : Texts {

    class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val untranslatable: Set<String>)

    companion object {
        val RES_DIR: File by lazy {
            listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        }

        private val cache = HashMap<String, Res>()

        /** 所有存在的语言目录限定符（"" 表示 values/） */
        fun qualifiers(): List<String> = RES_DIR.listFiles()!!
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .filter { d -> d.listFiles()!!.any { it.name.startsWith("strings") && it.name.endsWith(".xml") } }
            .map { it.name.removePrefix("values").removePrefix("-") }
            .sorted()

        fun load(q: String): Res = cache.getOrPut(q) {
            val dir = File(RES_DIR, if (q.isEmpty()) "values" else "values-$q")
            val strings = LinkedHashMap<String, String>()
            val plurals = LinkedHashMap<String, Map<String, String>>()
            val fixed = HashSet<String>()
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
            for (f in files) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
                val nodes = doc.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val n = nodes.item(i) as? Element ?: continue
                    val name = n.getAttribute("name")
                    when (n.tagName) {
                        "string" -> {
                            require(name !in strings) { "重复的键 $name（${f.path}）" }
                            strings[name] = decode(text(n))
                            if (n.getAttribute("translatable") == "false") fixed += name
                        }
                        "plurals" -> {
                            val m = LinkedHashMap<String, String>()
                            val items = n.getElementsByTagName("item")
                            for (k in 0 until items.length) {
                                val it = items.item(k) as Element
                                m[it.getAttribute("quantity")] = decode(text(it))
                            }
                            require(name !in plurals) { "重复的复数键 $name（${f.path}）" }
                            plurals[name] = m
                        }
                    }
                }
            }
            Res(strings, plurals, fixed)
        }

        private fun text(e: Element): String {
            val sb = StringBuilder()
            val c = e.childNodes
            for (i in 0 until c.length) {
                val n = c.item(i)
                if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) sb.append(n.nodeValue)
                else if (n.nodeType == Node.ELEMENT_NODE) sb.append(n.textContent)
            }
            return sb.toString()
        }

        /** 按 Android 规则处理：未加引号的连续空白合并为一个空格；\n \t \' \" \\ \@ \? 转义 */
        fun decode(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            var lastSpace = false
            val s = raw.trim()
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '\\' && i + 1 < s.length -> {
                        when (val n = s[i + 1]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            else -> out.append(n)
                        }
                        i += 2
                        lastSpace = false
                        continue
                    }
                    ch == '"' -> {
                        quoted = !quoted
                    }
                    ch.isWhitespace() && !quoted -> {
                        if (!lastSpace) out.append(' ')
                        lastSpace = true
                        i++
                        continue
                    }
                    else -> out.append(ch)
                }
                lastSpace = false
                i++
            }
            return out.toString()
        }

        private fun idNames(cls: String): Map<Int, String> =
            Class.forName("com.manus.kavitaink.R\$$cls").fields.associate { it.getInt(null) to it.name }

        private val stringNames by lazy { idNames("string") }
        private val pluralNames by lazy { idNames("plurals") }

        fun name(id: Int): String = stringNames[id] ?: pluralNames[id] ?: error("未知资源 id $id")

        /** CLDR 复数规则（只覆盖我们支持的语言） */
        fun quantity(lang: String, n: Int): String = when (lang) {
            "zh", "ja", "ko" -> "other"
            "fr", "pt" -> if (n == 0 || n == 1) "one" else "other"
            "ru" -> when {
                n % 10 == 1 && n % 100 != 11 -> "one"
                n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
                else -> "many"
            }
            else -> if (n == 1) "one" else "other"
        }
    }

    private val lang = if (qualifier.isEmpty()) "en" else qualifier.substringBefore('-')
    val locale: Locale = when {
        qualifier.isEmpty() -> Locale.ENGLISH
        qualifier.contains("-r") -> Locale(lang, qualifier.substringAfter("-r"))
        else -> Locale(lang)
    }
    private val res = load(qualifier)
    private val base = load("")

    fun raw(name: String): String = res.strings[name] ?: base.strings[name] ?: error("缺少文案 $name")

    override fun s(id: Int, vararg args: Any?): String {
        val t = raw(name(id))
        return if (args.isEmpty()) t else String.format(locale, t, *args)
    }

    override fun p(id: Int, n: Int, vararg args: Any?): String {
        val key = name(id)
        val m = res.plurals[key] ?: base.plurals[key] ?: error("缺少复数文案 $key")
        val t = m[quantity(lang, n)] ?: m["other"] ?: error("$key 缺少 other")
        return String.format(locale, t, *args)
    }
}

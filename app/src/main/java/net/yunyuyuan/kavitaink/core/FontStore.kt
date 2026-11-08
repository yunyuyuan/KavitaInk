package net.yunyuyuan.kavitaink.core

import net.yunyuyuan.kavitaink.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 小说字体（从 Kavita 下载或从本机导入）。纯 Kotlin，可在 JVM 上测试。
 *
 *   <dir>/fonts.json          索引：每个字体族的名称、来源、各变体的样式 / 字重 / 文件名
 *   <dir>/<字体ID>/<文件>      字体文件（ttf / otf / woff / woff2）
 *
 * 一个字体族可以有多个变体（常规、斜体、粗体……），阅读器为每个变体生成一条 @font-face。
 */
class FontStore(val dir: File) {

    data class Variant(val file: String, val style: String = "normal", val weight: String = "400")

    data class FontFamily(
        val id: String,
        val name: String,
        /** SOURCE_KAVITA / SOURCE_LOCAL */
        val source: String,
        val variants: List<Variant>,
        val bytes: Long,
        /** Kavita 上的字体族名（本机导入时为空） */
        val remote: String = "",
    )

    class BadFontException(res: Int) : LocalizedIOException(res)

    private val index = File(dir, "fonts.json")
    private val lock = Any()

    fun familyDir(id: String) = File(dir, id)

    fun fileOf(f: FontFamily, v: Variant) = File(familyDir(f.id), v.file)

    /** 已安装的字体（文件被删掉的变体 / 字体族会被忽略） */
    fun list(): List<FontFamily> = synchronized(lock) { readIndex() }.mapNotNull { f ->
        val vs = f.variants.filter { fileOf(f, it).isFile }
        if (vs.isEmpty()) null else f.copy(variants = vs, bytes = vs.sumOf { fileOf(f, it).length() })
    }.sortedBy { it.name.lowercase(Locale.ROOT) }

    fun get(id: String): FontFamily? = list().firstOrNull { it.id == id }

    /** 从本机导入一个字体文件；不是 TTF / OTF / WOFF / WOFF2 时抛出 [BadFontException] */
    fun importLocal(input: InputStream, fileName: String): FontFamily {
        dir.mkdirs()
        val tmp = File(dir, "import.part")
        val md = MessageDigest.getInstance("SHA-1")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val ext = sniff(tmp) ?: throw BadFontException(R.string.font_err_format)
            // 同一个文件重复导入只保留一份
            val id = "local-" + md.digest().take(6).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            val d = familyDir(id)
            d.mkdirs()
            val dest = File(d, "font.$ext")
            if (!dest.exists() && !tmp.renameTo(dest)) throw StorageException(R.string.err_save_file, dest.name)
            val name = fileName.substringAfterLast('/').substringBeforeLast('.').trim().ifBlank { id }
            val existing = synchronized(lock) { readIndex() }.firstOrNull { it.id == id }
            val f = existing ?: FontFamily(id, name, SOURCE_LOCAL, listOf(Variant(dest.name)), dest.length())
            put(f)
            return f.copy(bytes = dest.length())
        } finally {
            tmp.delete()
        }
    }

    /** 记录一个已下载完成的 Kavita 字体族 */
    fun putKavita(id: String, name: String, remote: String, variants: List<Variant>): FontFamily {
        val f = FontFamily(id, name, SOURCE_KAVITA, variants, variants.sumOf { File(familyDir(id), it.file).length() }, remote)
        put(f)
        return f
    }

    fun rename(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        synchronized(lock) {
            writeIndex(readIndex().map { if (it.id == id) it.copy(name = n) else it })
        }
    }

    /** 删除字体族及其全部文件 */
    fun remove(id: String) {
        synchronized(lock) { writeIndex(readIndex().filter { it.id != id }) }
        familyDir(id).deleteRecursively()
    }

    private fun put(f: FontFamily) = synchronized(lock) {
        writeIndex(readIndex().filter { it.id != f.id } + f)
    }

    private fun readIndex(): List<FontFamily> {
        if (!index.isFile) return emptyList()
        val a = runCatching { JSONArray(index.readText()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val vs = o.optJSONArray("variants") ?: JSONArray()
            FontFamily(
                id = o.s("id"),
                name = o.s("name"),
                source = o.s("source").ifBlank { SOURCE_LOCAL },
                variants = (0 until vs.length()).mapNotNull { k ->
                    vs.optJSONObject(k)?.let { v -> Variant(v.s("file"), v.s("style").ifBlank { "normal" }, v.s("weight").ifBlank { "400" }) }
                },
                bytes = o.optLong("bytes"),
                remote = o.s("remote"),
            ).takeIf { it.id.isNotBlank() }
        }
    }

    private fun writeIndex(list: List<FontFamily>) {
        dir.mkdirs()
        val a = JSONArray()
        list.forEach { f ->
            val vs = JSONArray()
            f.variants.forEach { v -> vs.put(JSONObject().put("file", v.file).put("style", v.style).put("weight", v.weight)) }
            a.put(
                JSONObject().put("id", f.id).put("name", f.name).put("source", f.source).put("remote", f.remote)
                    .put("bytes", f.bytes).put("variants", vs)
            )
        }
        val tmp = File(dir, "fonts.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }

    companion object {
        const val SOURCE_KAVITA = "kavita"
        const val SOURCE_LOCAL = "local"

        /** 按文件头判断格式，返回扩展名；不支持的格式（含 TTC 字体集合）返回 null */
        fun sniff(f: File): String? {
            if (f.length() < 12) return null
            val head = ByteArray(4)
            f.inputStream().use { if (it.read(head) != 4) return null }
            return when {
                head.contentEquals(byteArrayOf(0, 1, 0, 0)) -> "ttf"
                head.contentEquals("true".toByteArray()) -> "ttf"
                head.contentEquals("OTTO".toByteArray()) -> "otf"
                head.contentEquals("wOFF".toByteArray()) -> "woff"
                head.contentEquals("wOF2".toByteArray()) -> "woff2"
                else -> null
            }
        }

        fun mimeOf(file: String): String = when (file.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "font/ttf"
        }

        /** 只保留 CSS 安全的样式 / 字重取值 */
        fun cssStyle(s: String): String = s.lowercase(Locale.ROOT).takeIf { it == "normal" || it == "italic" || it == "oblique" } ?: "normal"

        fun cssWeight(w: String): String = w.trim().takeIf { Regex("^\\d{1,3}( \\d{1,3})?$").matches(it) } ?: "400"
    }
}

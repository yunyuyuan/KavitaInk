package net.yunyuyuan.kavitaink.core

import net.yunyuyuan.kavitaink.R
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 轻量 EPUB 读取器：直接从 zip 中按需读取资源（不解压到磁盘）。
 * 只解析阅读必需的信息：OPF 清单、书脊顺序、目录（EPUB3 nav 优先，其次 EPUB2 NCX）。
 */
class EpubBook(file: File) : Closeable {

    /** title 为空时显示「无标题」；section >= 0 表示书里没有目录、按书脊生成的第 N 节 */
    data class TocEntry(val title: String, val path: String, val fragment: String?, val level: Int, val section: Int = -1) {
        fun display(x: Texts): String = when {
            title.isNotBlank() -> title
            section >= 0 -> x.s(R.string.toc_section_n, section + 1)
            else -> x.s(R.string.toc_untitled)
        }
    }

    private val zip = ZipFile(file)
    private val names: Map<String, String>
    private val mime = HashMap<String, String>()

    val opfPath: String
    val title: String
    val spine: List<String>
    val toc: List<TocEntry>
    private val spineSet: Set<String>

    init {
        val map = HashMap<String, String>()
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (!e.isDirectory) map[e.name.trimStart('/').lowercase()] = e.name
        }
        names = map

        val containerText = readText("META-INF/container.xml")
        var opf: String? = null
        if (containerText != null) {
            runCatching {
                opf = Xml.elements(Xml.parse(containerText), "rootfile")
                    .firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
            }
        }
        if (opf == null || !exists(opf!!)) opf = names.values.firstOrNull { it.endsWith(".opf", true) }
        opfPath = opf ?: throw LocalizedIOException(R.string.err_epub_no_opf)
        val opfDir = opfPath.substringBeforeLast('/', "")
        val doc = Xml.parse(readText(opfPath) ?: throw LocalizedIOException(R.string.err_epub_read_opf))

        title = Xml.elements(doc, "title").firstOrNull()?.textContent?.trim().orEmpty()

        val items = HashMap<String, String>()
        var navPath: String? = null
        var ncxPath: String? = null
        for (el in Xml.elements(doc, "item")) {
            val id = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (href.isBlank()) continue
            val path = resolve(opfDir, href)
            val mt = el.getAttribute("media-type")
            items[id] = path
            if (mt.isNotBlank()) mime[path.lowercase()] = mt
            if (el.getAttribute("properties").split(' ').contains("nav")) navPath = path
            if (mt == "application/x-dtbncx+xml" && ncxPath == null) ncxPath = path
        }
        val spineEl = Xml.elements(doc, "spine").firstOrNull()
        spineEl?.getAttribute("toc")?.takeIf { it.isNotBlank() }?.let { id -> items[id]?.let { ncxPath = it } }

        var sp = Xml.elements(doc, "itemref").mapNotNull { items[it.getAttribute("idref")] }.filter { exists(it) }
        if (sp.isEmpty()) {
            sp = names.values.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }.sorted()
        }
        spine = sp.distinct()
        spineSet = spine.map { it.lowercase() }.toHashSet()

        var t: List<TocEntry> = emptyList()
        navPath?.let { p -> runCatching { t = parseNav(p) } }
        if (t.isEmpty()) ncxPath?.let { p -> runCatching { t = parseNcx(p) } }
        if (t.isEmpty()) t = spine.mapIndexed { i, p -> TocEntry("", p, null, 0, section = i) }
        toc = t
    }

    fun exists(path: String) = names.containsKey(path.trimStart('/').lowercase())

    fun read(path: String): ByteArray? {
        val name = names[path.trimStart('/').lowercase()] ?: return null
        synchronized(zip) {
            val e = zip.getEntry(name) ?: return null
            return zip.getInputStream(e).use { it.readBytes() }
        }
    }

    fun readText(path: String): String? = read(path)?.let { Xml.decode(it) }

    fun isSpineDoc(path: String) = spineSet.contains(path.trimStart('/').lowercase())

    fun spineIndexOf(path: String): Int {
        val p = path.trimStart('/').lowercase()
        return spine.indexOfFirst { it.lowercase() == p }
    }

    fun mimeOf(path: String): String {
        mime[path.trimStart('/').lowercase()]?.let { return it }
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xhtml", "xht", "xml" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }

    override fun close() {
        runCatching { zip.close() }
    }

    // ---------- 目录解析 ----------

    private fun parseNav(navPath: String): List<TocEntry> {
        val doc = Xml.parse(readText(navPath) ?: return emptyList())
        val dir = navPath.substringBeforeLast('/', "")
        val navs = Xml.elements(doc, "nav")
        val nav = navs.firstOrNull { el ->
            val type = el.getAttributeNS(OPS_NS, "type").ifBlank { el.getAttribute("epub:type") }
            type.split(' ').contains("toc")
        } ?: navs.firstOrNull() ?: return emptyList()
        val ol = Xml.children(nav).firstOrNull { it.localNameSafe() == "ol" } ?: return emptyList()
        val out = ArrayList<TocEntry>()
        walkOl(ol, dir, 0, out)
        return out
    }

    private fun walkOl(ol: Element, dir: String, level: Int, out: MutableList<TocEntry>) {
        for (li in Xml.children(ol)) {
            if (li.localNameSafe() != "li") continue
            val kids = Xml.children(li)
            val a = kids.firstOrNull { it.localNameSafe() == "a" }
            val label = (a ?: kids.firstOrNull { it.localNameSafe() == "span" })
                ?.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val href = a?.getAttribute("href").orEmpty()
            if (href.isNotBlank()) {
                out += TocEntry(label, resolve(dir, href), fragmentOf(href), level)
            }
            kids.firstOrNull { it.localNameSafe() == "ol" }?.let { walkOl(it, dir, level + 1, out) }
        }
    }

    private fun parseNcx(ncxPath: String): List<TocEntry> {
        val doc = Xml.parse(readText(ncxPath) ?: return emptyList())
        val dir = ncxPath.substringBeforeLast('/', "")
        val navMap = Xml.elements(doc, "navMap").firstOrNull() ?: return emptyList()
        val out = ArrayList<TocEntry>()
        walkNavPoints(navMap, dir, 0, out)
        return out
    }

    private fun walkNavPoints(parent: Element, dir: String, level: Int, out: MutableList<TocEntry>) {
        for (np in Xml.children(parent)) {
            if (np.localNameSafe() != "navPoint") continue
            val kids = Xml.children(np)
            val label = kids.firstOrNull { it.localNameSafe() == "navLabel" }
                ?.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val src = kids.firstOrNull { it.localNameSafe() == "content" }?.getAttribute("src").orEmpty()
            if (src.isNotBlank()) out += TocEntry(label, resolve(dir, src), fragmentOf(src), level)
            walkNavPoints(np, dir, level + 1, out)
        }
    }

    companion object {
        const val OPS_NS = "http://www.idpf.org/2007/ops"

        fun fragmentOf(href: String): String? =
            href.substringAfter('#', "").takeIf { it.isNotBlank() }?.let { urlDecode(it) }

        fun urlDecode(s: String): String =
            runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        /** 把相对 href 解析成 zip 内的完整路径（去掉 #片段、解码 %20、处理 ../） */
        fun resolve(baseDir: String, href: String): String {
            val clean = urlDecode(href.substringBefore('#').substringBefore('?'))
            val joined = if (clean.startsWith("/")) clean.trimStart('/') else if (baseDir.isEmpty()) clean else "$baseDir/$clean"
            val stack = ArrayList<String>()
            for (part in joined.split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> stack.add(part)
                }
            }
            return stack.joinToString("/")
        }
    }
}

private fun Element.localNameSafe(): String = localName ?: nodeName.substringAfter(':')

/** 宽松的 XML 解析：去掉 DOCTYPE（避免联网取 DTD），把 HTML 命名实体转成数字实体 */
object Xml {
    private val ENTITIES = mapOf(
        "nbsp" to 160, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "mdash" to 8212, "ndash" to 8211,
        "hellip" to 8230, "lsquo" to 8216, "rsquo" to 8217, "ldquo" to 8220, "rdquo" to 8221,
        "laquo" to 171, "raquo" to 187, "copy" to 169, "reg" to 174, "trade" to 8482, "middot" to 183,
        "bull" to 8226, "times" to 215, "divide" to 247, "deg" to 176, "shy" to 173, "zwnj" to 8204,
        "zwj" to 8205, "iexcl" to 161, "iquest" to 191, "sect" to 167, "para" to 182, "dagger" to 8224,
        "Dagger" to 8225, "prime" to 8242, "Prime" to 8243, "eacute" to 233, "egrave" to 232,
        "agrave" to 224, "aacute" to 225, "ccedil" to 231, "uuml" to 252, "ouml" to 246, "auml" to 228,
    )

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()))) {
            return String(bytes, Charsets.UTF_16)
        }
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val enc = Regex("encoding=[\"']([A-Za-z0-9_\\-]+)[\"']").find(head)?.groupValues?.get(1)
        val cs = enc?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, cs).removePrefix("\uFEFF")
    }

    fun parse(text: String): Document {
        var s = text.removePrefix("\uFEFF")
        s = s.replace(Regex("<\\?xml[^>]*\\?>"), "")
        s = s.replace(Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*])?\\s*>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("&([A-Za-z][A-Za-z0-9]*);")) { m ->
            when (val n = m.groupValues[1]) {
                "amp", "lt", "gt", "quot", "apos" -> m.value
                else -> ENTITIES[n]?.let { "&#$it;" } ?: ""
            }
        }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isValidating = false
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { f.isExpandEntityReferences = false }
        val b = f.newDocumentBuilder()
        b.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        b.setErrorHandler(null)
        return b.parse(InputSource(StringReader(s)))
    }

    /** 按本地名递归查找元素（不依赖具体 DOM 实现对命名空间通配符的支持） */
    fun elements(doc: Document, local: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    if ((c.localName ?: c.nodeName.substringAfter(':')) == local) out += c
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(doc)
        return out
    }

    fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var c = e.firstChild
        while (c != null) {
            if (c is Element) out += c
            c = c.nextSibling
        }
        return out
    }
}

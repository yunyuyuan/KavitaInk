package net.yunyuyuan.kavitaink.core

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 直接从 CBZ / ZIP 原文件读取漫画页面（不解压到磁盘）。
 *
 * - 只取图片条目，跳过目录、__MACOSX、隐藏文件
 * - 按完整路径做自然排序（page2 排在 page10 前面），与 Kavita 的顺序一致
 * - 文件名不是 UTF-8（老的中文压缩包常见 GBK）时自动改用 GBK 打开
 *
 * 纯 Kotlin，可在 JVM 上测试。读取是同步的：同一时间只读一个条目。
 */
class ZipPages private constructor(private val zip: ZipFile, private val entries: List<ZipEntry>) : AutoCloseable {

    val count: Int get() = entries.size

    fun name(i: Int): String = entries[i].name

    /** 读出第 i 页的原始字节（一页通常几百 KB，一次读完便于先读尺寸再解码） */
    @Synchronized
    fun bytes(i: Int): ByteArray = zip.getInputStream(entries[i]).use { it.readBytes() }

    @Synchronized
    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

        fun isImage(name: String): Boolean {
            val base = name.substringAfterLast('/')
            if (base.startsWith(".")) return false
            if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) return false
            return base.substringAfterLast('.', "").lowercase() in IMAGE_EXT
        }

        /** 打开压缩包；里面没有图片时抛出 IOException */
        fun open(f: File): ZipPages {
            val zip = openZip(f)
            try {
                val list = imageEntries(zip)
                if (list.isEmpty()) throw IOException("no images in ${f.name}")
                return ZipPages(zip, list)
            } catch (t: Throwable) {
                runCatching { zip.close() }
                throw t
            }
        }

        private fun openZip(f: File): ZipFile {
            // 文件名不是合法 UTF-8 时：Android 在枚举条目时抛 IllegalArgumentException，
            // 新版 JDK 在打开时就抛 ZipException（bad entry name）。两种都改用 GBK 再试一次
            var z: ZipFile? = null
            try {
                z = ZipFile(f)
                imageEntries(z)
                return z
            } catch (e: Exception) {
                if (e !is IllegalArgumentException && e !is java.util.zip.ZipException) throw e
                runCatching { z?.close() }
                return try {
                    ZipFile(f, Charset.forName("GBK")).also { imageEntries(it) }
                } catch (e2: Exception) {
                    throw e
                }
            }
        }

        private fun imageEntries(z: ZipFile): List<ZipEntry> {
            val out = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (!e.isDirectory && isImage(e.name)) out += e
            }
            return out.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        }

        /** 自然排序：数字按数值比较，其余不区分大小写 */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i
                    while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j
                    while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    // 数值相同（如 01 与 1）：位数少的在前
                    val lenDiff = (ei - i) - (ej - j)
                    if (lenDiff != 0) return lenDiff
                    i = ei
                    j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}

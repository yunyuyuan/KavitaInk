package com.manus.kavitaink.core

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 从 Kavita 服务器获取字体（只在用户手动打开「从 Kavita 获取」时联网）：
 *  - 列表：GET /api/Font/all（Auth Key 可用）
 *  - 系统自带字体（provider = 1）：GET /assets/fonts/<字体族>/<文件名>，不需要认证
 *  - 用户上传字体（provider = 2）：GET /api/Font?fontId=<id>
 * 大小用 Range: bytes=0-0 读取 Content-Range（/api/Font 不支持 HEAD）。
 */
class KavitaFonts(private val api: KavitaApi) {

    data class RemoteFont(
        val id: Int,
        val family: String,
        val name: String,
        val provider: Int,
        val fileName: String,
        val style: String,
        val weight: String,
    )

    data class RemoteFamily(val family: String, val fonts: List<RemoteFont>) {
        /** 本地字体 ID：同一字体族重复下载会覆盖同一个目录 */
        val localId: String get() = "kavita-" + family.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "font" }
        val userUploaded: Boolean get() = fonts.any { it.provider == 2 }
    }

    fun list(): List<RemoteFamily> {
        val a = JSONArray(api.text("GET", "/api/Font/all"))
        val fonts = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            RemoteFont(
                id = o.optInt("id"),
                family = o.s("family").ifBlank { o.s("name") },
                name = o.s("name"),
                provider = o.optInt("provider", 1),
                fileName = o.s("fileName"),
                style = o.s("style").ifBlank { "normal" },
                weight = o.s("weight").ifBlank { "400" },
            )
        }.filter { it.fileName.isNotBlank() } // id = 1 的「Default」没有文件，表示不指定字体
        return fonts.groupBy { it.family }.map { (fam, list) -> RemoteFamily(fam, list) }
            // 用户上传的字体（通常是中文字体）排在前面
            .sortedWith(compareBy<RemoteFamily>({ !it.userUploaded }, { it.family.lowercase(Locale.ROOT) }))
    }

    fun url(f: RemoteFont): String =
        if (f.provider == 1) "${api.base}/assets/fonts/${seg(f.family)}/${seg(f.fileName)}"
        else "${api.base}/api/Font?fontId=${f.id}"

    /** 文件大小（字节），读不到时返回 -1 */
    fun size(f: RemoteFont): Long {
        val c = URL(url(f)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", KavitaApi.UA)
            c.setRequestProperty("x-api-key", api.apiKey)
            c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            if (code == 206) {
                c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { return it }
            }
            if (code == 200) return c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            return -1
        } finally {
            c.disconnect()
        }
    }

    /**
     * 下载整个字体族的所有变体到字体目录（断点续传，可取消），完成后写入索引。
     * sizes 是事先查询到的各文件大小（与 fam.fonts 对应，未知为 -1）。
     */
    fun download(
        fam: RemoteFamily,
        sizes: List<Long>,
        store: FontStore,
        downloader: Downloader,
        progress: (done: Long, total: Long) -> Unit,
        stopped: () -> Boolean,
    ): FontStore.FontFamily {
        val dir = store.familyDir(fam.localId)
        dir.mkdirs()
        if (!dir.isDirectory) throw StorageException(com.manus.kavitaink.R.string.err_mkdir)
        val total = sizes.filter { it > 0 }.sum()
        var before = 0L
        val variants = fam.fonts.mapIndexed { i, f ->
            val name = safeName(i, f.fileName)
            val expected = sizes.getOrElse(i) { -1L }
            downloader.fetch(url(f), File(dir, name), expected, { done, _, _ -> progress(before + done, total) }, stopped)
            before += File(dir, name).length()
            FontStore.Variant(name, FontStore.cssStyle(f.style), FontStore.cssWeight(f.weight))
        }
        return store.putKavita(fam.localId, fam.family, fam.family, variants)
    }

    companion object {
        /** URL 路径段编码（空格编码为 %20 而不是 +） */
        fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** 本地文件名：序号 + 原扩展名（原文件名可能含任意字符） */
        fun safeName(i: Int, fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in setOf("ttf", "otf", "woff", "woff2") } ?: "ttf"
            return "v$i.$ext"
        }
    }
}

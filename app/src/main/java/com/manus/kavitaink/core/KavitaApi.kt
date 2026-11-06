package com.manus.kavitaink.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * 极简 Kavita 客户端。认证统一使用 Auth Key：
 * 普通请求放在 `x-api-key` 请求头；/api/Reader/image 必须放在 `apiKey` 查询参数（服务端模型校验要求）。
 * 只有用户主动操作时才会发起请求，没有任何后台轮询或同步。
 */
class KavitaApi(baseUrl: String, val apiKey: String) {
    val base: String = normalizeBase(baseUrl)
    val serverKey: String = serverKeyOf(base)

    // ---------- 业务接口 ----------

    fun version(): String = text("GET", "/api/Plugin/version?apiKey=${enc(apiKey)}").trim().trim('"')

    fun libraries(): List<Library> {
        val arr = JSONArray(text("GET", "/api/Library/libraries"))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Library(it.optInt("id"), it.s("name"), it.optInt("type"))
        }.sortedBy { it.name }
    }

    fun seriesPage(libraryId: Int, page: Int, pageSize: Int = 50): SeriesPage {
        val body = JSONObject()
            .put(
                "statements", JSONArray().put(
                    JSONObject().put("comparison", 0).put("field", 19).put("value", libraryId.toString())
                )
            )
            .put("combination", 1)
            .put("sortOptions", JSONObject().put("sortField", 1).put("isAscending", true))
            .put("limitTo", 0)
        val c = connect("POST", "/api/Series/v2?PageNumber=$page&PageSize=$pageSize", body.toString())
        try {
            val json = c.inputStream.use { readText(it) }
            val pag = c.getHeaderField("Pagination")
            var totalPages = page
            var totalItems = -1
            if (!pag.isNullOrBlank()) {
                runCatching {
                    val p = JSONObject(pag)
                    totalPages = p.optInt("totalPages", page)
                    totalItems = p.optInt("totalItems", -1)
                }
            }
            val arr = JSONArray(json)
            val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::series)
            return SeriesPage(items, page, totalPages, totalItems)
        } finally {
            c.disconnect()
        }
    }

    fun search(query: String): List<SeriesItem> {
        val o = JSONObject(text("GET", "/api/Search/search?queryString=${enc(query)}&includeChapterAndFiles=false"))
        val arr = o.optJSONArray("series") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            SeriesItem(
                id = it.optInt("seriesId"),
                name = it.s("name"),
                libraryId = it.optInt("libraryId"),
                libraryName = it.s("libraryName"),
                format = it.optInt("format", Fmt.UNKNOWN),
                pages = 0,
            )
        }
    }

    fun seriesInfo(seriesId: Int): SeriesItem = series(JSONObject(text("GET", "/api/Series/$seriesId")))

    fun chapters(seriesId: Int, seriesFormat: Int): List<ChapterItem> =
        Parsers.chapters(text("GET", "/api/Series/volumes?seriesId=$seriesId"), seriesId, seriesFormat)

    fun chapterPages(chapterId: Int): Int =
        JSONObject(text("GET", "/api/Reader/chapter-info?chapterId=$chapterId")).optInt("pages")

    fun coverBytes(seriesId: Int): ByteArray {
        val c = connect("GET", "/api/Image/series-cover?seriesId=$seriesId", accept = "image/*")
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun downloadUrl(chapterId: Int) = "$base/api/Download/chapter?chapterId=$chapterId"

    fun pageUrl(chapterId: Int, page: Int, extractPdf: Boolean) =
        "$base/api/Reader/image?chapterId=$chapterId&page=$page&apiKey=${enc(apiKey)}&extractPdf=$extractPdf"

    // ---------- HTTP 基础 ----------

    fun text(method: String, path: String, body: String? = null): String {
        val c = connect(method, path, body)
        try {
            return c.inputStream.use { readText(it) }
        } finally {
            c.disconnect()
        }
    }

    /** 打开连接并检查状态码；调用方负责读取并 disconnect */
    fun connect(
        method: String,
        pathOrUrl: String,
        body: String? = null,
        rangeFrom: Long = 0,
        accept: String = "application/json",
        readTimeoutMs: Int = 60_000,
        onOpen: ((HttpURLConnection) -> Unit)? = null,
    ): HttpURLConnection {
        val url = URL(if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl else base + pathOrUrl)
        val c = url.openConnection() as HttpURLConnection
        // 在真正联网之前交出连接对象：暂停 / 取消时可直接 disconnect()，不必等超时
        onOpen?.invoke(c)
        c.connectTimeout = 15_000
        c.readTimeout = readTimeoutMs
        c.requestMethod = method
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        if (apiKey.isNotEmpty()) c.setRequestProperty("x-api-key", apiKey)
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = runCatching { c.errorStream?.use { readText(it) } }.getOrNull().orEmpty()
            c.disconnect()
            throw ApiException(code, describe(code, msg))
        }
        return c
    }

    private fun series(o: JSONObject) = SeriesItem(
        id = o.optInt("id"),
        name = o.s("name").ifBlank { o.s("localizedName") },
        libraryId = o.optInt("libraryId"),
        libraryName = o.s("libraryName"),
        format = o.optInt("format", Fmt.UNKNOWN),
        pages = o.optInt("pages"),
    )

    companion object {
        const val UA = "KavitaInk/1.0 (Android; e-ink)"

        /** 用户名密码登录，只为换取 Auth Key；密码不会被保存 */
        fun login(baseUrl: String, username: String, password: String): KavitaApi {
            val anon = KavitaApi(baseUrl, "")
            val body = JSONObject().put("username", username).put("password", password)
            val o = JSONObject(anon.text("POST", "/api/Account/login", body.toString()))
            var key = o.s("apiKey")
            if (key.isBlank()) {
                val keys = o.optJSONArray("authKeys")
                if (keys != null && keys.length() > 0) key = keys.optJSONObject(0)?.s("key").orEmpty()
            }
            if (key.isBlank()) throw ApiException(0, "登录成功，但服务器没有返回 Auth Key，请改用 Auth Key 方式连接")
            return KavitaApi(baseUrl, key)
        }

        fun normalizeBase(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val i = s.indexOf("/api/", ignoreCase = true)
            if (i > 0) s = s.substring(0, i)
            s = s.trimEnd('/')
            if (s.endsWith("/api", true)) s = s.dropLast(4)
            return s.trimEnd('/')
        }

        /** 支持直接粘贴 Kavita 的 OPDS 链接：http://host:5000/api/opds/<key> */
        fun keyFromOpdsUrl(raw: String): String? =
            Regex("/api/opds/([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)

        fun serverKeyOf(base: String): String {
            val md = MessageDigest.getInstance("SHA-1").digest(base.lowercase().toByteArray())
            return md.take(5).joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
        }

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun readText(input: InputStream): String = String(input.readBytes(), Charsets.UTF_8)

        private fun describe(code: Int, body: String): String {
            val detail = extractMessage(body)
            return when (code) {
                400 -> "请求被拒绝 (400)" + (if (detail.isNotBlank()) "：$detail" else "")
                401 -> "认证失败：用户名/密码或 Auth Key 不正确，或已过期"
                403 -> "没有权限（下载需要该账号在 Kavita 中拥有 Download 角色）"
                404 -> "服务器上找不到该内容 (404)"
                in 500..599 -> "服务器错误 ($code)" + (if (detail.isNotBlank()) "：$detail" else "")
                else -> "请求失败 ($code)" + (if (detail.isNotBlank()) "：$detail" else "")
            }
        }

        private fun extractMessage(body: String): String {
            val t = body.trim()
            if (t.isEmpty()) return ""
            val msg = runCatching {
                val o = JSONObject(t)
                o.s("message").ifBlank { o.s("title") }.ifBlank { o.s("detail") }
            }.getOrNull() ?: t.trim('"')
            return msg.take(120)
        }

        /** 把各种网络异常翻译成中文提示 */
        fun friendlyError(t: Throwable?): String = when (t) {
            null -> "未知错误"
            is ApiException -> t.message ?: "请求失败"
            is UnknownHostException -> "无法解析服务器地址，请检查网址或网络"
            is ConnectException -> "无法连接服务器，请检查地址、端口和网络"
            is SocketTimeoutException -> "连接超时，请检查网络"
            is SSLException -> "HTTPS 证书校验失败（自签名证书需先安装到系统）"
            is java.net.MalformedURLException -> "服务器地址格式不正确"
            is org.json.JSONException -> "服务器返回的数据无法解析（地址是否指向 Kavita？）"
            // 我们自己抛出的中文提示原样显示；系统底层的英文报错（unexpected end of stream、
            // Connection reset、Socket closed 等）统一显示为“网络连接中断”
            is IOException -> t.message?.takeIf { m -> m.any { it.code in 0x4E00..0x9FFF } } ?: "网络连接中断"
            else -> t.message ?: t.javaClass.simpleName
        }

        /**
         * 是否属于“过一会儿再试可能就好了”的错误：断网、超时、连接被重置、服务器 5xx / 429。
         * 认证失败、无权限、404、证书错误、格式不支持等不会自动重试。
         */
        fun isTransient(t: Throwable?): Boolean = when (t) {
            null -> false
            is ApiException -> t.code == 408 || t.code == 429 || t.code in 500..599
            is StorageException -> false
            is SSLException -> false
            is java.net.MalformedURLException -> false
            is IOException -> true
            else -> false
        }
    }
}

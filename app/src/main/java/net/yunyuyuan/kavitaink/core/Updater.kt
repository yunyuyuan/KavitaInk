package net.yunyuyuan.kavitaink.core

import net.yunyuyuan.kavitaink.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：只在用户手动点击时访问 GitHub 的 releases/latest，不做任何定时或后台检查。
 * 下载复用 Downloader 的断点续传；给 GitHub 的请求不带 Kavita 的 Auth Key。
 */
object Updater {
    const val REPO = "yunyuyuan/KavitaInk"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class Release(
        val tag: String,
        /** 去掉前缀 v 的版本名，例如 1.2.0 */
        val version: String,
        val notes: String,
        val page: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    fun latest(): Release {
        val c = URL(LATEST_API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", KavitaApi.UA)
        try {
            val code = c.responseCode
            when {
                code == 404 -> throw ApiException(code, R.string.update_err_none)
                code == 403 || code == 429 -> throw ApiException(code, R.string.update_err_rate)
                code !in 200..299 -> throw ApiException(code, R.string.update_err_http, "", code)
            }
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parse(text)
        } finally {
            c.disconnect()
        }
    }

    /** 解析 releases/latest 的 JSON；选第一个 .apk 资产，跳过调试签名包（文件名带 -debugsigned） */
    fun parse(json: String): Release {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val assets = o.optJSONArray("assets")
        var name = ""
        var url = ""
        var size = -1L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name")
                if (!n.endsWith(".apk", ignoreCase = true) || n.contains("debug", ignoreCase = true)) continue
                name = n
                url = a.optString("browser_download_url")
                size = a.optLong("size", -1L)
                break
            }
        }
        if (url.isEmpty()) throw ApiException(0, R.string.update_err_no_apk, "", tag)
        return Release(
            tag = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            notes = o.optString("body").let { if (o.isNull("body")) "" else it },
            page = o.optString("html_url").ifBlank { RELEASES_PAGE },
            apkName = safeName(name),
            apkUrl = url,
            apkSize = size,
        )
    }

    /** 版本号的数字部分：1.2.3-abc → [1, 2, 3]；和 versionCode 的计算规则一致（忽略 -xxx 后缀） */
    fun numbers(v: String): List<Int> {
        val p = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }
        return (p + listOf(0, 0, 0)).take(3)
    }

    fun compare(a: String, b: String): Int {
        val x = numbers(a)
        val y = numbers(b)
        for (i in 0 until 3) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }

    fun isNewer(remote: String, current: String) = compare(remote, current) > 0

    /** 资产名只保留安全字符，作为本地文件名（也用于 ContentProvider 的路径校验） */
    fun safeName(n: String): String {
        val s = n.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
        return if (s.endsWith(".apk", ignoreCase = true)) s else "$s.apk"
    }

    /** 下载安装包到 dir；未完成的部分保留为 .part，下次接着下载 */
    fun download(r: Release, dir: File, progress: Downloader.Progress, stopped: () -> Boolean, abortHook: (Downloader) -> Unit = {}): File {
        dir.mkdirs()
        if (!dir.isDirectory) throw StorageException(R.string.err_mkdir)
        // 清理旧版本的安装包
        dir.listFiles()?.forEach { f ->
            if (f.name != r.apkName && f.name != r.apkName + ".part") f.delete()
        }
        val dest = File(dir, r.apkName)
        // 给 GitHub 的请求不带 Kavita 的 Auth Key：apiKey 为空时不会加 x-api-key 头
        val d = Downloader(KavitaApi("https://github.com", ""), Storage(dir, dir))
        abortHook(d)
        d.fetch(r.apkUrl, dest, r.apkSize, progress, stopped)
        if (r.apkSize > 0 && dest.length() != r.apkSize) {
            dest.delete()
            throw LocalizedIOException(R.string.err_incomplete, dest.length(), r.apkSize)
        }
        return dest
    }

    /** 去掉 Markdown 里最常见的标记，方便在纯文本里显示更新说明 */
    fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
        line.replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^\\s*[-*]\\s+"), "• ")
            .replace("**", "")
            .replace("`", "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}

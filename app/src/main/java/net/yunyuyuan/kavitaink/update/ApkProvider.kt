package net.yunyuyuan.kavitaink.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 把下载好的安装包以 content:// 的形式交给系统安装器（Android 7+ 不允许直接传 file://）。
 * 只读，只提供更新目录下文件名合法的 .apk，不导出，靠临时 URI 授权访问。
 */
class ApkProvider : ContentProvider() {

    companion object {
        const val MIME = "application/vnd.android.package-archive"
        private val NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")

        fun authority(ctx: Context) = ctx.packageName + ".apk"

        /** 安装包存放目录：优先外部缓存目录（Android 7 以下用 file:// 安装时安装器需要能读到） */
        fun dir(ctx: Context): File = File(ctx.externalCacheDir ?: ctx.cacheDir, "update")

        fun uriFor(ctx: Context, f: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(ctx))
            .appendPath(f.name)
            .build()
    }

    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment.orEmpty()
        if (uri.pathSegments.size != 1 || !NAME.matches(name)) throw FileNotFoundException(name)
        val f = File(dir(ctx), name)
        if (!f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun getType(uri: Uri): String = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

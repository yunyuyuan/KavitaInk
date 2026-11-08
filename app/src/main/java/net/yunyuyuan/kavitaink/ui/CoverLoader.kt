package net.yunyuyuan.kavitaink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * 封面加载：优先本地下载目录中的封面，其次磁盘缓存，最后才联网获取一次。
 * 解码时按显示尺寸降采样并使用 RGB_565，内存占用约为原图的 1/16 ~ 1/8。
 */
object CoverLoader {
    private val mem = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun bind(iv: ImageView, key: String, local: File?, cache: File?, remote: (() -> ByteArray)?) {
        iv.tag = key
        val hit = mem.get(key)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        val w = iv.layoutParams?.width?.takeIf { it > 0 } ?: 120
        val h = iv.layoutParams?.height?.takeIf { it > 0 } ?: 170
        exec.execute {
            val bmp = runCatching {
                var f: File? = local?.takeIf { it.isFile && it.length() > 0 }
                    ?: cache?.takeIf { it.isFile && it.length() > 0 }
                if (f == null && remote != null && cache != null) {
                    val bytes = remote()
                    if (bytes.isNotEmpty()) {
                        cache.parentFile?.mkdirs()
                        cache.writeBytes(bytes)
                        f = cache
                    }
                }
                f?.let { decode(it, w, h) }
            }.getOrNull()
            if (bmp != null) {
                mem.put(key, bmp)
                main.post { if (iv.tag == key) iv.setImageBitmap(bmp) }
            }
        }
    }

    fun clearMemory() = mem.evictAll()

    private fun decode(f: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = s
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}

package com.manus.kavitaink.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.widget.EditText
import android.widget.TextView
import com.manus.kavitaink.Prefs
import com.manus.kavitaink.R
import com.manus.kavitaink.core.FontStore
import com.manus.kavitaink.core.error
import com.manus.kavitaink.core.formatBytes

/**
 * 字体管理：从 Kavita 获取或从本机导入（TTF / OTF / WOFF / WOFF2），每一项用该字体显示一行预览。
 * 点内置字体直接选用；点已安装的字体可以选用、重命名或删除。字体只用于小说（EPUB）阅读。
 */
class FontsActivity : BaseActivity() {

    private data class Row(val id: String, val name: String, val sub: String, val family: FontStore.FontFamily?, val typeface: Typeface?)

    private lateinit var adapter: RowAdapter<Row>
    private lateinit var status: TextView
    private val faces = HashMap<String, Typeface?>()

    companion object {
        private const val REQ_PICK = 41
        private const val REQ_KAVITA = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (prefs.api() != null) {
            actions += getString(R.string.fonts_from_kavita) to {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, KavitaFontsActivity::class.java), REQ_KAVITA)
            }
        }
        actions += getString(R.string.fonts_import) to ::pick
        val (head, _) = header(getString(R.string.fonts_title), back = true, actions = actions)
        root.addView(head)
        status = statusLine()
        root.addView(status)
        val lv = einkList()
        pagedList = lv
        adapter = RowAdapter(emptyList(), { createRow() }, { v, r, _ -> bindRow(v, r) })
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ -> tapped(adapter.items[pos]) }
        lv.setOnItemLongClickListener { _, _, pos, _ -> tapped(adapter.items[pos]); true }
        root.addView(lv, lp(MATCH, 0, 1f))
        root.addView(pagerBar(lv))
        setContentView(root)
        refresh()
    }

    private fun createRow(): android.view.View {
        val box = vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minimumHeight = dp(64)
        }
        val title = label("", 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val preview = label("", 18f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, dp(2)) }
        val sub = label("", 13f, color = 0xFF333333.toInt())
        box.addView(title)
        box.addView(preview)
        box.addView(sub)
        box.tag = arrayOf(title, preview, sub)
        return box
    }

    private fun bindRow(v: android.view.View, r: Row) {
        val (title, preview, sub) = tags(v)
        val current = r.id == prefs.fontId
        title.text = if (current) "✓ ${r.name}" else r.name
        preview.text = getString(R.string.font_preview)
        preview.typeface = r.typeface ?: Typeface.DEFAULT
        sub.text = r.sub
        v.setBackgroundColor(Color.WHITE)
    }

    /** 用常规体（没有就用第一个变体）做预览；WOFF / WOFF2 系统无法直接加载，只在阅读时生效 */
    private fun previewOf(f: FontStore.FontFamily): Pair<Typeface?, Boolean> {
        val v = f.variants.firstOrNull { it.style == "normal" && it.weight.startsWith("400") } ?: f.variants.first()
        val file = app.fonts.fileOf(f, v)
        val web = file.name.endsWith(".woff") || file.name.endsWith(".woff2")
        if (web) return null to true
        val tf = faces.getOrPut(f.id + "/" + file.name) { runCatching { Typeface.createFromFile(file) }.getOrNull() }
        return tf to false
    }

    private fun refresh(msg: String? = null) {
        bg({
            app.fonts.list().map { f ->
                val (tf, web) = previewOf(f)
                val parts = ArrayList<String>()
                parts += getString(if (f.source == FontStore.SOURCE_KAVITA) R.string.fonts_source_kavita else R.string.fonts_source_local)
                if (f.variants.size > 1) parts += resources.getQuantityString(R.plurals.fonts_variants, f.variants.size, f.variants.size)
                parts += formatBytes(f.bytes)
                if (web) parts += getString(R.string.fonts_preview_web)
                else if (tf == null) parts += getString(R.string.fonts_preview_failed)
                Row(f.id, f.name, parts.joinToString(" · "), f, tf)
            }
        }) { list, _ ->
            val builtins = listOf(
                Row(Prefs.FONT_DEFAULT, getString(R.string.font_book_default), getString(R.string.fonts_builtin_default_sub), null, Typeface.DEFAULT),
                Row(Prefs.FONT_SERIF, getString(R.string.font_serif), getString(R.string.fonts_builtin_sub), null, Typeface.SERIF),
                Row(Prefs.FONT_SANS, getString(R.string.font_sans), getString(R.string.fonts_builtin_sub), null, Typeface.SANS_SERIF),
            )
            val installed = list.orEmpty()
            adapter.update(builtins + installed)
            status.text = msg ?: if (installed.isEmpty()) getString(R.string.fonts_empty_hint)
            else resources.getQuantityString(R.plurals.fonts_count, installed.size, installed.size)
        }
    }

    private fun tapped(r: Row) {
        if (r.family == null) {
            use(r)
            return
        }
        val items = arrayOf(getString(R.string.fonts_use), getString(R.string.fonts_rename), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> use(r)
                    1 -> rename(r)
                    2 -> confirm(getString(R.string.fonts_delete_title), getString(R.string.fonts_delete_msg, r.name), getString(R.string.delete)) { delete(r) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun use(r: Row) {
        prefs.fontId = r.id
        adapter.notifyDataSetChanged()
        status.text = getString(R.string.fonts_now_using, r.name)
    }

    private fun rename(r: Row) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(r.name)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.fonts_rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    app.fonts.rename(r.id, n)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(r: Row) {
        app.fonts.remove(r.id)
        faces.keys.filter { it.startsWith(r.id + "/") }.forEach { faces.remove(it) }
        // 删除的是正在使用的字体：用回书籍默认字体
        if (prefs.fontId == r.id) prefs.fontId = Prefs.FONT_DEFAULT
        refresh()
    }

    private fun pick() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "font/ttf", "font/otf", "font/sfnt", "font/woff", "font/woff2", "application/x-font-ttf",
                    "application/x-font-otf", "application/font-sfnt", "application/font-woff", "application/vnd.ms-opentype",
                    "application/octet-stream",
                )
            )
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(open, REQ_PICK)
        } catch (_: ActivityNotFoundException) {
            // 部分墨水屏阅读器精简了系统文件选择器
            val get = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(get, getString(R.string.fonts_import)), REQ_PICK)
            } catch (_: ActivityNotFoundException) {
                status.text = getString(R.string.fonts_no_picker)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_KAVITA) {
            refresh()
            return
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = getString(R.string.fonts_importing)
        bg({
            val name = displayName(uri)
            val input = contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
            input.use { app.fonts.importLocal(it, name) }
        }) { f, err ->
            if (f == null) {
                status.text = getString(R.string.fonts_import_failed, x.error(err))
                return@bg
            }
            prefs.fontId = f.id
            refresh(getString(R.string.fonts_imported, f.name))
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun tags(v: android.view.View) = v.tag as Array<TextView>
}

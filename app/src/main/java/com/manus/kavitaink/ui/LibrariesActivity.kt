package com.manus.kavitaink.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.core.KavitaApi
import com.manus.kavitaink.core.Library

class LibrariesActivity : BaseActivity() {

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var query: EditText
    private val adapter = RowAdapter<Library>(
        emptyList(),
        { TwoLineRow(this, false).root },
        { v, lib, _ ->
            val r = TwoLineRow.of(v)
            r.title.text = lib.name
            r.sub.text = lib.typeLabel
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.api() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        val root = root()
        root.addView(header("书库", back = true, actions = listOf("刷新" to ::load)).first)

        val search = hbox().apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        query = EditText(this).apply {
            hint = "搜索系列名"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            background = resources.getDrawable(com.manus.kavitaink.R.drawable.btn_bg, theme)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { _, _, _ -> doSearch(); true }
        }
        search.addView(query, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
        search.addView(button("搜索") { doSearch() })
        root.addView(search)
        root.addView(hline())

        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list = einkList()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.let { SeriesListActivity.library(this, it.id, it.name) }
        }
        root.addView(list, lp(MATCH, 0, 1f))
        root.addView(pagerBar(list))
        setContentView(root)
        root.requestFocus()
        pagedList = list
        load()
    }

    private fun load() {
        val api = app.api() ?: return
        status.text = "正在加载书库…"
        bg({ api.libraries() }) { libs, err ->
            if (libs == null) {
                status.text = "加载失败：" + KavitaApi.friendlyError(err)
                return@bg
            }
            adapter.update(libs)
            status.text = if (libs.isEmpty()) "该账号没有可访问的书库" else "共 ${libs.size} 个书库 · ${api.base}"
        }
    }

    private fun doSearch() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        SeriesListActivity.search(this, q)
    }
}

package com.manus.kavitaink.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.core.Fmt
import com.manus.kavitaink.core.KavitaApi
import com.manus.kavitaink.core.SeriesItem
import com.manus.kavitaink.core.Storage
import java.io.File

class SeriesListActivity : BaseActivity() {

    companion object {
        fun library(ctx: Context, id: Int, name: String) =
            ctx.startActivity(Intent(ctx, SeriesListActivity::class.java).putExtra("lib", id).putExtra("title", name))

        fun search(ctx: Context, q: String) =
            ctx.startActivity(Intent(ctx, SeriesListActivity::class.java).putExtra("q", q).putExtra("title", "搜索：$q"))

        const val PAGE_SIZE = 50
    }

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var more: Button
    private lateinit var api: KavitaApi
    private var libraryId = -1
    private var query: String? = null
    private val items = ArrayList<SeriesItem>()
    private var page = 0
    private var totalPages = 1
    private var totalItems = -1
    private var loading = false
    private lateinit var adapter: RowAdapter<SeriesItem>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = app.api() ?: run { finish(); return }
        libraryId = intent.getIntExtra("lib", -1)
        query = intent.getStringExtra("q")
        val showCovers = prefs.showCovers

        adapter = RowAdapter(
            emptyList(),
            { TwoLineRow(this, showCovers).root },
            { v, s, _ ->
                val r = TwoLineRow.of(v)
                r.title.text = s.name
                val sb = StringBuilder(Fmt.label(s.format))
                if (s.pages > 0 && s.format != Fmt.EPUB) sb.append(" · ").append(s.pages).append(" 页")
                if (query != null && s.libraryName.isNotBlank()) sb.append(" · ").append(s.libraryName)
                r.sub.text = sb
                r.cover?.let { iv ->
                    CoverLoader.bind(
                        iv, "srv:${api.serverKey}:${s.id}",
                        File(app.storage.seriesDir(api.serverKey, s.id), Storage.COVER),
                        File(app.coverCacheDir, "${api.serverKey}_${s.id}"),
                    ) { api.coverBytes(s.id) }
                }
            },
        )

        val root = root()
        root.addView(header(intent.getStringExtra("title") ?: "系列", back = true).first)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list = einkList()
        val footer = vbox().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        more = button("加载更多") { loadNext() }
        footer.addView(more, lp(MATCH, WRAP))
        list.addFooterView(footer, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { SeriesActivity.open(this, api.serverKey, it.id, it.name, it.format, online = true) }
        }
        root.addView(list, lp(MATCH, 0, 1f))
        root.addView(pagerBar(list))
        setContentView(root)
        pagedList = list
        more.visibility = View.GONE
        loadNext()
    }

    private fun loadNext() {
        if (loading) return
        loading = true
        more.isEnabled = false
        status.text = "正在加载…"
        val q = query
        bg({
            if (q != null) api.search(q).let { com.manus.kavitaink.core.SeriesPage(it, 1, 1, it.size) }
            else api.seriesPage(libraryId, page + 1, PAGE_SIZE)
        }) { r, err ->
            loading = false
            more.isEnabled = true
            if (r == null) {
                status.text = "加载失败：" + KavitaApi.friendlyError(err)
                more.text = "重试"
                more.visibility = View.VISIBLE
                return@bg
            }
            page = r.page
            totalPages = r.totalPages
            totalItems = r.totalItems
            items.addAll(r.items)
            adapter.update(ArrayList(items))
            val total = if (totalItems >= 0) totalItems else items.size
            status.text = when {
                items.isEmpty() -> if (q != null) "没有找到匹配的系列" else "该书库没有内容"
                else -> "共 $total 个系列 · 已显示 ${items.size}"
            }
            if (page < totalPages) {
                more.text = "加载更多（第 ${page + 1}/$totalPages 页）"
                more.visibility = View.VISIBLE
            } else {
                more.visibility = View.GONE
            }
        }
    }
}

package com.manus.kavitaink.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.R
import com.manus.kavitaink.core.error
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
            ctx.startActivity(Intent(ctx, SeriesListActivity::class.java).putExtra("q", q).putExtra("title", ctx.getString(R.string.series_search_title, q)))

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
                val sb = StringBuilder(Fmt.label(s.format, x))
                if (s.pages > 0 && s.format != Fmt.EPUB) sb.append(" · ").append(resources.getQuantityString(R.plurals.series_pages, s.pages, s.pages))
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
        root.addView(header(intent.getStringExtra("title") ?: getString(R.string.series_title), back = true).first)
        status = statusLine()
        root.addView(status)
        root.addView(hline())
        list = einkList()
        val footer = vbox().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        more = button(getString(R.string.series_more)) { loadNext() }
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
        status.text = getString(R.string.loading)
        val q = query
        bg({
            if (q != null) api.search(q).let { com.manus.kavitaink.core.SeriesPage(it, 1, 1, it.size) }
            else api.seriesPage(libraryId, page + 1, PAGE_SIZE)
        }) { r, err ->
            loading = false
            more.isEnabled = true
            if (r == null) {
                status.text = getString(R.string.load_failed, x.error(err))
                more.text = getString(R.string.retry)
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
                items.isEmpty() -> if (q != null) getString(R.string.series_no_match) else getString(R.string.series_empty_lib)
                else -> resources.getQuantityString(R.plurals.series_count, total, total, items.size)
            }
            if (page < totalPages) {
                more.text = getString(R.string.series_more_page, page + 1, totalPages)
                more.visibility = View.VISIBLE
            } else {
                more.visibility = View.GONE
            }
        }
    }
}

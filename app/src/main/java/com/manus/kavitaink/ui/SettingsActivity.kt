package com.manus.kavitaink.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ListView
import com.manus.kavitaink.StorageAccess
import com.manus.kavitaink.Lang
import com.manus.kavitaink.Prefs
import com.manus.kavitaink.R
import com.manus.kavitaink.core.formatBytes
import com.manus.kavitaink.reader.ReaderOptions
import com.manus.kavitaink.reader.StatusBarDialog
import java.util.Locale

/**
 * 设置页分组：通用 / 小说 / 漫画 / 翻页与刷新 / 下载与存储 / 服务器 / 关于。
 * 排版、双页、状态栏用的是和阅读菜单相同的对话框，两边的选项和取值完全一致。
 */
class SettingsActivity : BaseActivity() {

    private class Item(
        val title: () -> String,
        val value: () -> String = { "" },
        val header: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter<Item>
    private var usedText = ""
    private var cacheText = ""

    private fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)
    private fun withNote(v: String, note: Int) = getString(R.string.title_dot, v, getString(note))
    private fun refreshList() = adapter.notifyDataSetChanged()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usedText = getString(R.string.set_calculating)
        cacheText = usedText
        adapter = RowAdapter(items(), { TwoLineRow(this, false).root }) { v, it, _ ->
            val r = TwoLineRow.of(v)
            r.title.text = it.title()
            if (it.header) {
                r.title.typeface = Typeface.DEFAULT_BOLD
                r.title.textSize = 14f
                r.sub.visibility = View.GONE
                r.root.minimumHeight = dp(36)
                r.root.setBackgroundColor(0xFFE6E6E6.toInt())
            } else {
                r.title.typeface = Typeface.DEFAULT
                r.title.textSize = 17f
                val value = it.value()
                r.sub.text = value
                r.sub.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
                r.root.minimumHeight = dp(56)
                r.root.setBackgroundColor(0xFFFFFFFF.toInt())
            }
        }
        val root = root()
        root.addView(header(getString(R.string.set_title), back = true).first)
        list = einkList()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.action?.let { act ->
                act()
                refreshList()
            }
        }
        root.addView(list, lp(MATCH, 0, 1f))
        root.addView(pagerBar(list))
        setContentView(root)
        pagedList = list
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        bg({ app.storage.usedBytes() to (app.coverCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) }) { r, _ ->
            usedText = formatBytes(r?.first ?: 0).ifBlank { "0 KB" }
            cacheText = formatBytes(r?.second ?: 0).ifBlank { "0 KB" }
            refreshList()
        }
    }

    private fun group(title: Int) = Item({ getString(title) }, header = true)
    private fun item(title: Int, value: () -> String = { "" }, action: (() -> Unit)? = null) = Item({ getString(title) }, value, action = action)

    private fun items(): List<Item> {
        val p = prefs
        return listOf(
            // ---------- 通用 ----------
            group(R.string.grp_general),
            item(R.string.set_language, ::languageName, ::chooseLanguage),
            item(R.string.set_orientation, { orientationName(p.orientation) }) {
                val labels = (0..4).map { orientationName(it) }.toTypedArray()
                choose(getString(R.string.set_orientation), labels, p.orientation.coerceIn(0, 4)) {
                    p.orientation = it
                    applyOrientation()
                    refreshList()
                }
            },

            // ---------- 小说 ----------
            group(R.string.grp_novel),
            item(R.string.set_font, { fontName(p.fontId) }) { startActivity(Intent(this, FontsActivity::class.java)) },
            item(R.string.layout_title, { getString(if (p.bookStyle) R.string.set_layout_book else R.string.set_layout_custom) }) {
                optionsDialog(getString(R.string.layout_title), ReaderOptions.layout(this, p) {}) { refreshList() }
            },
            item(R.string.set_status_bar, { statusSummary(true) }) {
                StatusBarDialog.show(this, p.statusBar(true), true) { refreshList() }
            },
            item(R.string.set_font_size, { "${p.fontZoom}%" }) {
                val labels = FONT_ZOOMS.map { "$it%" }.toTypedArray()
                choose(getString(R.string.set_font_size), labels, FONT_ZOOMS.indexOf(p.fontZoom)) { p.fontZoom = FONT_ZOOMS[it]; refreshList() }
            },
            item(R.string.set_line_height, { lineLabel(p.lineHeight) }) {
                val labels = LINE_HEIGHTS.map(::lineLabel).toTypedArray()
                choose(getString(R.string.set_line_height), labels, LINE_HEIGHTS.indexOf(p.lineHeight)) { p.lineHeight = LINE_HEIGHTS[it]; refreshList() }
            },
            item(R.string.set_force_black, { withNote(onOff(p.forceBlack), R.string.set_force_black_note) }) { p.forceBlack = !p.forceBlack },

            // ---------- 漫画 ----------
            group(R.string.grp_manga),
            item(R.string.spread_mode, { ReaderOptions.spreadModeName(this, p.spreadMode) }) {
                optionsDialog(getString(R.string.img_layout_title), ReaderOptions.spread(this, p) {}) { refreshList() }
            },
            item(R.string.set_crop, { ReaderOptions.cropName(this, p) }) {
                val labels = arrayOf(getString(R.string.off), getString(R.string.crop_white), getString(R.string.crop_white_black))
                val cur = if (!p.cropMargins) 0 else if (p.cropBlack) 2 else 1
                choose(getString(R.string.set_crop), labels, cur) {
                    p.cropMargins = it > 0
                    p.cropBlack = it == 2
                    refreshList()
                }
            },
            item(R.string.set_status_bar, { statusSummary(false) }) {
                StatusBarDialog.show(this, p.statusBar(false), false) { refreshList() }
            },
            item(R.string.set_direction, { getString(if (p.mangaRtl) R.string.set_direction_rtl else R.string.dir_ltr) }) { p.mangaRtl = !p.mangaRtl },
            item(R.string.set_fit, { getString(if (p.fitMode == 1) R.string.set_fit_width else R.string.set_fit_screen) }) {
                val labels = arrayOf(getString(R.string.set_fit_screen), getString(R.string.set_fit_width))
                choose(getString(R.string.set_fit), labels, p.fitMode.coerceIn(0, 1)) { p.fitMode = it; refreshList() }
            },
            item(R.string.set_contrast, { withNote(onOff(p.contrast), R.string.set_contrast_note) }) { p.contrast = !p.contrast },

            // ---------- 翻页与刷新 ----------
            group(R.string.grp_paging),
            item(R.string.set_volume_keys, { onOff(p.volumeKeys) }) { p.volumeKeys = !p.volumeKeys },
            item(R.string.set_swap_volume, { getString(if (p.swapVolume) R.string.set_vol_up_next else R.string.set_vol_down_next) }) {
                p.swapVolume = !p.swapVolume
            },
            item(R.string.set_tap_zones, { tapModes()[p.tapMode.coerceIn(0, 1)] }) {
                choose(getString(R.string.set_tap_zones), tapModes(), p.tapMode.coerceIn(0, 1)) { p.tapMode = it; refreshList() }
            },
            item(R.string.set_swipe, { onOff(p.swipe) }) { p.swipe = !p.swipe },
            item(R.string.set_full_refresh, { refreshLabels()[REFRESH_VALUES.indexOf(p.fullRefreshEvery).coerceAtLeast(0)] }) {
                choose(getString(R.string.set_full_refresh), refreshLabels(), REFRESH_VALUES.indexOf(p.fullRefreshEvery)) {
                    p.fullRefreshEvery = REFRESH_VALUES[it]; refreshList()
                }
            },

            // ---------- 下载与存储 ----------
            group(R.string.grp_storage),
            item(R.string.set_location, {
                app.storage.contentRoot.absolutePath + "\n" +
                    getString(if (StorageAccess.granted(this)) R.string.set_perm_granted else R.string.set_perm_missing)
            }) {
                if (!StorageAccess.granted(this)) withStorageAccess { refreshList() }
            },
            item(R.string.set_concurrency, { withNote(p.downloadConcurrency.toString(), R.string.set_concurrency_note) }) {
                val labels = (1..Prefs.MAX_CONCURRENCY).map { it.toString() }.toTypedArray()
                choose(getString(R.string.set_concurrency), labels, p.downloadConcurrency - 1) {
                    p.downloadConcurrency = it + 1
                    refreshList()
                }
            },
            item(R.string.set_used, { usedText }),
            item(R.string.set_covers, { withNote(onOff(p.showCovers), R.string.set_covers_note) }) { p.showCovers = !p.showCovers },
            item(R.string.set_clear_cache, { withNote(cacheText, R.string.set_clear_cache_note) }) {
                app.coverCacheDir.deleteRecursively()
                CoverLoader.clearMemory()
                cacheText = "0 KB"
            },

            // ---------- 服务器 ----------
            group(R.string.grp_server),
            item(R.string.set_server, {
                when {
                    p.apiKey.isBlank() -> getString(R.string.set_server_none)
                    p.username.isNotBlank() -> getString(R.string.set_server_user, p.serverUrl, p.username)
                    else -> getString(R.string.set_server_key, p.serverUrl)
                }
            }) { startActivity(Intent(this, LoginActivity::class.java)) },
            item(R.string.set_logout, { getString(R.string.set_logout_note) }) {
                if (p.apiKey.isNotBlank()) confirm(getString(R.string.set_logout), getString(R.string.set_logout_msg), getString(R.string.set_logout_ok)) {
                    p.apiKey = ""
                    refreshList()
                }
            },

            // ---------- 关于 ----------
            group(R.string.grp_about),
            Item({ getString(R.string.about_version, versionName()) }, { getString(R.string.about_tagline) }),
            item(R.string.update_check, { getString(R.string.update_check_note) }) {
                startActivity(Intent(this, UpdateActivity::class.java))
            },
        )
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun languageName(): String {
        val cur = Lang.current(this)
        if (cur.isEmpty()) return getString(R.string.lang_system)
        return Lang.OPTIONS.firstOrNull { it.tag == cur }?.name ?: cur
    }

    private fun chooseLanguage() {
        val labels = (listOf(getString(R.string.lang_system)) + Lang.OPTIONS.map { it.name }).toTypedArray()
        val cur = Lang.current(this)
        val idx = if (cur.isEmpty()) 0 else Lang.OPTIONS.indexOfFirst { it.tag == cur } + 1
        choose(getString(R.string.set_language), labels, idx.coerceAtLeast(0)) { which ->
            val tag = if (which == 0) "" else Lang.OPTIONS[which - 1].tag
            if (tag != cur) Lang.set(this, tag)
        }
    }

    /** 状态栏摘要：关闭，或列出位置和显示的项；并提示时间只在翻页时更新 */
    private fun statusSummary(novel: Boolean): String {
        val sb = prefs.statusBar(novel)
        if (!sb.on) return getString(R.string.off)
        val names = ArrayList<String>()
        if (sb.items and Prefs.SB_PAGE != 0) names += getString(R.string.sb_item_page)
        if (sb.items and Prefs.SB_PERCENT != 0) names += getString(if (novel) R.string.sb_item_percent_book else R.string.sb_item_percent_chapter)
        if (sb.items and Prefs.SB_CHAPTER != 0) names += getString(R.string.sb_item_chapter)
        if (sb.items and Prefs.SB_TIME != 0) names += getString(R.string.sb_item_time)
        if (sb.items and Prefs.SB_BATTERY != 0) names += getString(R.string.sb_item_battery)
        val pos = getString(if (sb.top) R.string.set_sb_top else R.string.set_sb_bottom)
        val head = (listOf(pos) + names).joinToString(getString(R.string.list_sep))
        return if (sb.items and Prefs.SB_TIME != 0) head + "\n" + getString(R.string.set_sb_time_note) else head
    }

    private fun tapModes() = arrayOf(getString(R.string.set_tap_three), getString(R.string.set_tap_two))

    private fun refreshLabels() = arrayOf(
        getString(R.string.set_refresh_off),
        resources.getQuantityString(R.plurals.set_refresh_every, 5, 5),
        resources.getQuantityString(R.plurals.set_refresh_every, 10, 10),
        resources.getQuantityString(R.plurals.set_refresh_every, 20, 20),
    )

    private fun lineLabel(v: Int) =
        if (v == 0) getString(R.string.value_default)
        else getString(R.string.set_line_x, String.format(Locale.getDefault(), "%.1f", v / 100.0))

    companion object {
        val FONT_ZOOMS = listOf(80, 90, 100, 110, 120, 135, 150, 175, 200)
        val LINE_HEIGHTS = listOf(0, 130, 150, 160, 180, 200)
        val REFRESH_VALUES = listOf(0, 5, 10, 20)
    }
}

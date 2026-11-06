package com.manus.kavitaink.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ListView
import com.manus.kavitaink.core.formatBytes

class SettingsActivity : BaseActivity() {

    private class Item(
        val title: String,
        val value: () -> String = { "" },
        val header: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter<Item>
    private var usedText = "计算中…"
    private var cacheText = "计算中…"

    private fun onOff(b: Boolean) = if (b) "开" else "关"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = RowAdapter(items(), { TwoLineRow(this, false).root }) { v, it, _ ->
            val r = TwoLineRow.of(v)
            r.title.text = it.title
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
        root.addView(header("设置", back = true).first)
        list = einkList()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos)?.action?.let { act ->
                act()
                adapter.notifyDataSetChanged()
            }
        }
        root.addView(list, lp(MATCH, 0, 1f))
        root.addView(pagerBar(list))
        setContentView(root)
        pagedList = list
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        bg({ app.storage.usedBytes() to (app.coverCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) }) { r, _ ->
            usedText = formatBytes(r?.first ?: 0).ifBlank { "0 KB" }
            cacheText = formatBytes(r?.second ?: 0).ifBlank { "0 KB" }
            adapter.notifyDataSetChanged()
        }
    }

    private fun items(): List<Item> {
        val p = prefs
        return listOf(
            Item("账户", header = true),
            Item("服务器", {
                if (p.apiKey.isBlank()) "未连接 · 点击登录"
                else p.serverUrl + (if (p.username.isNotBlank()) "（${p.username}）" else "（Auth Key）")
            }) { startActivity(Intent(this, LoginActivity::class.java)) },
            Item("退出登录", { "清除保存的 Auth Key，已下载内容保留" }) {
                if (p.apiKey.isNotBlank()) confirm("退出登录", "退出后仍可阅读已下载内容。", "退出") {
                    p.apiKey = ""
                    adapter.notifyDataSetChanged()
                }
            },

            Item("翻页", header = true),
            Item("音量键翻页", { onOff(p.volumeKeys) }) { p.volumeKeys = !p.volumeKeys },
            Item("交换音量键方向", { if (p.swapVolume) "音量+ 下一页" else "音量- 下一页" }) { p.swapVolume = !p.swapVolume },
            Item("点击区域", { TAP_MODES[p.tapMode.coerceIn(0, 1)] }) {
                choose("点击区域", TAP_MODES, p.tapMode) { p.tapMode = it; adapter.notifyDataSetChanged() }
            },
            Item("滑动翻页", { onOff(p.swipe) }) { p.swipe = !p.swipe },

            Item("漫画 / PDF", header = true),
            Item("阅读方向", { if (p.mangaRtl) "从右到左（日漫）" else "从左到右" }) { p.mangaRtl = !p.mangaRtl },
            Item("缩放方式", { FIT_MODES[p.fitMode.coerceIn(0, 1)] }) {
                choose("缩放方式", FIT_MODES, p.fitMode) { p.fitMode = it; adapter.notifyDataSetChanged() }
            },
            Item("增强对比度（灰度）", { onOff(p.contrast) + " · 适合扫描件偏灰的漫画" }) { p.contrast = !p.contrast },
            Item("显示页码", { onOff(p.showPageNum) }) { p.showPageNum = !p.showPageNum },

            Item("小说 EPUB", header = true),
            Item("字号", { "${p.fontZoom}%" }) {
                val labels = FONT_ZOOMS.map { "$it%" }.toTypedArray()
                choose("字号", labels, FONT_ZOOMS.indexOf(p.fontZoom)) { p.fontZoom = FONT_ZOOMS[it]; adapter.notifyDataSetChanged() }
            },
            Item("行距", { lineLabel(p.lineHeight) }) {
                val labels = LINE_HEIGHTS.map(::lineLabel).toTypedArray()
                choose("行距", labels, LINE_HEIGHTS.indexOf(p.lineHeight)) { p.lineHeight = LINE_HEIGHTS[it]; adapter.notifyDataSetChanged() }
            },
            Item("页边距", { "${p.margin} dp" }) {
                val labels = MARGINS.map { "$it dp" }.toTypedArray()
                choose("页边距", labels, MARGINS.indexOf(p.margin)) { p.margin = MARGINS[it]; adapter.notifyDataSetChanged() }
            },
            Item("字体", { FONTS[p.fontFamily.coerceIn(0, 2)] }) {
                choose("字体", FONTS, p.fontFamily) { p.fontFamily = it; adapter.notifyDataSetChanged() }
            },
            Item("强制纯黑文字", { onOff(p.forceBlack) + " · 去除灰色字和彩色背景，墨水屏更清晰" }) { p.forceBlack = !p.forceBlack },

            Item("墨水屏", header = true),
            Item("定期全刷（消除残影）", { REFRESH_LABELS[REFRESH_VALUES.indexOf(p.fullRefreshEvery).coerceAtLeast(0)] }) {
                choose("定期全刷", REFRESH_LABELS, REFRESH_VALUES.indexOf(p.fullRefreshEvery)) {
                    p.fullRefreshEvery = REFRESH_VALUES[it]; adapter.notifyDataSetChanged()
                }
            },
            Item("列表显示封面", { onOff(p.showCovers) + " · 关闭可减少流量与刷新" }) { p.showCovers = !p.showCovers },

            Item("存储", header = true),
            Item("下载位置", { app.storage.root.absolutePath }),
            Item("离线内容占用", { usedText }),
            Item("清除封面缓存", { cacheText + " · 不影响已下载内容" }) {
                app.coverCacheDir.deleteRecursively()
                CoverLoader.clearMemory()
                cacheText = "0 KB"
            },

            Item("关于", header = true),
            Item("Kavita墨读 1.0.0", { "只做下载与离线阅读：不同步进度、无后台任务、无动画" }),
        )
    }

    private fun lineLabel(v: Int) = if (v == 0) "书籍默认" else String.format(java.util.Locale.US, "%.1f 倍", v / 100.0)

    companion object {
        val TAP_MODES = arrayOf("左右三分：左上一页 / 右下一页 / 中间菜单", "左右两分：左上一页 / 右下一页（长按菜单）")
        val FIT_MODES = arrayOf("适应屏幕（整页）", "适应宽度（长图分段翻页）")
        val FONT_ZOOMS = listOf(80, 90, 100, 110, 120, 135, 150, 175, 200)
        val LINE_HEIGHTS = listOf(0, 130, 150, 160, 180, 200)
        val MARGINS = listOf(8, 12, 16, 24, 32)
        val FONTS = arrayOf("书籍默认", "衬线（宋体类）", "无衬线（黑体类）")
        val REFRESH_VALUES = listOf(0, 5, 10, 20)
        val REFRESH_LABELS = arrayOf("关闭（交给系统处理）", "每 5 页", "每 10 页", "每 20 页")
    }
}

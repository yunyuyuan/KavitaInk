package net.yunyuyuan.kavitaink.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import net.yunyuyuan.kavitaink.R
import net.yunyuyuan.kavitaink.ui.orientationName
import net.yunyuyuan.kavitaink.core.Kind
import net.yunyuyuan.kavitaink.core.LocalChapter
import net.yunyuyuan.kavitaink.ui.BaseActivity
import net.yunyuyuan.kavitaink.ui.HeaderBar
import net.yunyuyuan.kavitaink.ui.MATCH
import net.yunyuyuan.kavitaink.ui.SettingsActivity
import net.yunyuyuan.kavitaink.ui.WRAP
import net.yunyuyuan.kavitaink.ui.button
import net.yunyuyuan.kavitaink.ui.dp
import net.yunyuyuan.kavitaink.ui.hbox
import net.yunyuyuan.kavitaink.ui.hline
import net.yunyuyuan.kavitaink.ui.label
import net.yunyuyuan.kavitaink.ui.lp
import net.yunyuyuan.kavitaink.ui.vbox
import net.yunyuyuan.kavitaink.Prefs
import java.io.File
import java.util.Date
import kotlin.math.abs

object Readers {
    fun open(ctx: Context, c: LocalChapter) {
        val cls = if (c.kind == Kind.EPUB) EpubReaderActivity::class.java else ImageReaderActivity::class.java
        ctx.startActivity(Intent(ctx, cls).putExtra("dir", c.dir.absolutePath))
    }
}

/**
 * 阅读器公共逻辑：
 *  - 点击分区 / 滑动 / 音量键 / 实体翻页键翻页；中间点击或长按呼出菜单
 *  - 菜单 = 顶部按钮栏（返回、书名、设置）+ 底部操作区；操作区分「跳转 / 画面或文字 / 屏幕」三页，
 *    分页标签固定在最下面，记住上次停留的页；菜单与提示都是静态覆盖层，没有任何动画
 *  - 从设置页返回时，离开期间改过的设置立即生效（没改就什么都不做）
 *  - 可选“每 N 页黑屏闪一下”做全刷，消除墨水屏残影
 *  - 读到章节末尾时，再翻一页进入下一个已下载的章节
 *  - 可配置的状态栏（顶部或底部的固定细条，阅读内容为它让出空间）
 */
abstract class ReaderActivity : BaseActivity() {

    companion object {
        /** 菜单分页：跳转（跳页 / 目录、上下章）、画面或文字、屏幕（方向、状态栏、全刷） */
        const val TAB_NAV = 0
        const val TAB_VIEW = 1
        const val TAB_SCREEN = 2
        private val TAB_IDS = intArrayOf(R.id.reader_tab_0, R.id.reader_tab_1, R.id.reader_tab_2)
    }

    protected lateinit var chapter: LocalChapter
    /** 阅读内容容器：子类把阅读视图加到这里（尺寸已扣除状态栏） */
    protected lateinit var frame: FrameLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var statusBar: StatusBarView
    private lateinit var hint: TextView
    private lateinit var flash: View
    private lateinit var menu: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var menuRows: LinearLayout
    private lateinit var menuInfo: TextView
    private lateinit var menuTabs: List<Button>
    /** 当前菜单页：TAB_NAV / TAB_VIEW / TAB_SCREEN */
    private var menuTab = TAB_NAV
    /** 当前已应用的状态栏设置，用来判断从设置页回来时是否要重新应用 */
    private var sbApplied = ""

    private var turns = 0
    private var endArmed = false
    private var startArmed = false
    private var siblings: List<LocalChapter>? = null

    protected open val rtl: Boolean get() = false
    /** true = 小说（使用小说的状态栏设置），false = 漫画 / PDF */
    protected abstract val novel: Boolean

    /** 创建阅读内容视图，并通过 frame.addView 加入 */
    protected abstract fun setup()
    protected abstract fun nextPage()
    protected abstract fun prevPage()
    /** 「跳转」页上方的按钮（跳页 / 目录）；上一章、下一章由基类加在后面 */
    protected abstract fun buildNavMenu()
    /** 第二页的按钮：漫画是「画面」，小说是「文字」 */
    protected abstract fun buildViewMenu()
    /** 第二页的标题 */
    protected abstract val viewTabTitle: Int
    /** 菜单里显示的进度说明 */
    protected abstract fun progressText(): String
    /** 状态栏的页码，例如「12 / 80」，未知时返回空串 */
    protected abstract fun statusPage(): String
    /** 状态栏的进度百分比，未知时返回 -1 */
    protected abstract fun statusPercent(): Int
    /** 状态栏的章节名 */
    protected open fun statusChapter(): String = chapter.title(x)
    protected abstract fun savePosition()

    /**
     * 回到阅读器时调用（例如从设置页返回）：应用离开期间改过的显示 / 排版设置，没改就什么都不做。
     * resized = 状态栏变化使阅读区域尺寸改变；这种情况子类还会收到尺寸变化并按新设置重新排版，不必重复。
     */
    protected open fun applyChangedPrefs(resized: Boolean) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = intent.getStringExtra("dir")?.let { app.storage.readChapter(File(it)) }
        if (c == null) {
            Toast.makeText(this, R.string.reader_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        chapter = c
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystemUi()

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(rootFrame)
        frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        rootFrame.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        statusBar = StatusBarView(this).apply { setOnTouchListener(touch) }
        rootFrame.addView(statusBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        applyStatusBar()
        setup()

        hint = label("", 15f).apply {
            setBackgroundResource(R.drawable.btn_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        rootFrame.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(56); leftMargin = dp(24); rightMargin = dp(24)
        })

        menu = vbox().apply {
            visibility = View.GONE
            isClickable = true
        }
        menu.addView(hline(2))
        val inner = vbox().apply { setPadding(dp(10), dp(8), dp(10), dp(10)) }
        menuInfo = label("", 14f)
        menuRows = vbox()
        inner.addView(menuInfo)
        inner.addView(menuRows)
        // 分页标签放在最下面：切换时标签位置不动，只换上面的按钮
        inner.addView(hline(1).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
        menuTab = prefs.menuTab(novel).coerceIn(TAB_NAV, TAB_SCREEN)
        val tabs = hbox().apply { setPadding(0, dp(8), 0, 0) }
        menuTabs = listOf(R.string.reader_tab_go, viewTabTitle, R.string.reader_tab_screen).mapIndexed { i, res ->
            button(getString(res)) { selectTab(i) }.also {
                it.id = TAB_IDS[i]
                tabs.addView(it, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        }
        inner.addView(tabs, lp(MATCH, WRAP))
        menu.addView(inner)
        rootFrame.addView(menu, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // 顶部按钮栏：返回（退出阅读）+ 书名 + 设置，和底部操作区一起显示 / 隐藏
        val title = label(chapter.meta.seriesName + " · " + chapter.title(x), 16f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        val exit = button(getString(R.string.back)) { finish() }
        val bar = HeaderBar(this, exit, title, listOf(button(getString(R.string.set_title), ::openSettings))).apply {
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minimumHeight = dp(52)
        }
        topBar = vbox().apply {
            visibility = View.GONE
            isClickable = true
            addView(bar, lp(MATCH, WRAP))
            addView(hline(2))
        }
        rootFrame.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        flash = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        rootFrame.addView(flash, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------- 状态栏 ----------

    private val sbPrefs: Prefs.StatusBarPrefs get() = prefs.statusBar(novel)

    private fun sbKey(): String = sbPrefs.let { "${it.on},${it.items},${it.top},${it.size}" }

    /**
     * 按设置显示 / 隐藏状态栏，并调整阅读区域（尺寸变化后子类会收到重新排版）。
     * 返回阅读区域的尺寸是否改变（只换了上 / 下位置、高度不变时为 false）。
     */
    protected fun applyStatusBar(): Boolean {
        val sb = sbPrefs
        sbApplied = sbKey()
        val on = sb.on && sb.items != 0
        statusBar.size = sb.size
        val h = statusBar.barHeight
        statusBar.visibility = if (on) View.VISIBLE else View.GONE
        (statusBar.layoutParams as FrameLayout.LayoutParams).gravity = if (sb.top) Gravity.TOP else Gravity.BOTTOM
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        val before = lp.topMargin + lp.bottomMargin
        lp.topMargin = if (on && sb.top) h else 0
        lp.bottomMargin = if (on && !sb.top) h else 0
        frame.layoutParams = lp
        statusBar.requestLayout()
        updateInfo()
        return lp.topMargin + lp.bottomMargin != before
    }

    /** 刷新状态栏内容（翻页、打开章节、关闭菜单时调用） */
    protected fun updateInfo() {
        if (!::statusBar.isInitialized || statusBar.visibility != View.VISIBLE) return
        val items = sbPrefs.items
        val left = if (items and Prefs.SB_CHAPTER != 0) statusChapter() else ""
        val parts = ArrayList<String>(3)
        if (items and Prefs.SB_PAGE != 0) statusPage().takeIf { it.isNotEmpty() }?.let { parts += it }
        if (items and Prefs.SB_PERCENT != 0) statusPercent().takeIf { it >= 0 }?.let { parts += x.s(R.string.percent_n, it) }
        if (items and Prefs.SB_TIME != 0) parts += DateFormat.getTimeFormat(this).format(Date())
        val battery = if (items and Prefs.SB_BATTERY != 0) StatusBarView.batteryLevel(this) else -1
        statusBar.update(left, parts.joinToString("  ·  "), battery)
    }

    // ---------- 菜单 ----------

    protected val menuVisible get() = ::menu.isInitialized && menu.visibility == View.VISIBLE

    protected fun showMenu() {
        hideHint()
        rebuildMenu()
        menu.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
    }

    protected fun hideMenu() {
        if (::menu.isInitialized && menu.visibility == View.VISIBLE) {
            menu.visibility = View.GONE
            topBar.visibility = View.GONE
            updateInfo()
        }
    }

    /** 打开设置页；先收起菜单，回来时直接看到按新设置显示的内容 */
    private fun openSettings() {
        hideMenu()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    protected fun rebuildMenu() {
        menuInfo.text = progressText()
        menuRows.removeAllViews()
        menuTabs.forEachIndexed { i, b -> styleTab(b, i == menuTab) }
        when (menuTab) {
            TAB_NAV -> {
                buildNavMenu()
                val prev = sibling(-1)
                val next = sibling(+1)
                menuRow(
                    getString(R.string.reader_prev_chapter) to { prev?.let(::openChapter) ?: showHint(getString(R.string.reader_no_prev)) },
                    getString(R.string.reader_next_chapter) to { next?.let(::openChapter) ?: showHint(getString(R.string.reader_no_next)) },
                )
            }
            TAB_VIEW -> buildViewMenu()
            else -> {
                menuRow(
                    getString(R.string.reader_orientation, orientationName(prefs.orientation)) to ::cycleOrientation,
                    getString(R.string.reader_status_bar) to {
                        StatusBarDialog.show(this, sbPrefs, novel) { applyStatusBar() }
                    },
                )
                menuRow(getString(R.string.reader_full_refresh) to { hideMenu(); flashRefresh() })
            }
        }
    }

    private fun selectTab(i: Int) {
        if (i == menuTab) return
        menuTab = i
        prefs.setMenuTab(novel, i)
        rebuildMenu()
    }

    /** 选中的标签黑底白字，其余细边框 */
    private fun styleTab(b: Button, selected: Boolean) {
        b.setBackgroundResource(if (selected) R.drawable.tab_sel else R.drawable.tab_bg)
        b.setTextColor(if (selected) Color.WHITE else Color.BLACK)
        b.paint.isFakeBoldText = selected
        b.isSelected = selected
    }

    /** 菜单里的快捷切换：跟随系统 → 竖屏 → 横屏 → 跟随系统（同时写回全局设置） */
    private fun cycleOrientation() {
        prefs.orientation = when (prefs.orientation) {
            0 -> 1
            1 -> 2
            else -> 0
        }
        applyOrientation()
        rebuildMenu()
    }

    protected fun menuRow(vararg items: Pair<String, () -> Unit>): List<Button> {
        // 行间距用行的上内边距，不能用按钮的上外边距：横向 LinearLayout 垂直居中时会把带上外边距的子控件
        // 再往下推半个外边距，按钮底边（连同下边框）会被行裁掉
        val row = hbox().apply { setPadding(0, dp(6), 0, 0) }
        val out = items.mapIndexed { i, (name, fn) ->
            button(name, fn).also { row.addView(it, lp(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            }) }
        }
        menuRows.addView(row, lp(MATCH, WRAP))
        return out
    }

    /** 把菜单按钮显示为灰色且不可点 */
    protected fun Button.disable() {
        isEnabled = false
        setTextColor(0xFF999999.toInt())
    }

    // ---------- 提示 ----------

    protected fun showHint(text: String) {
        if (!::hint.isInitialized) return
        hint.text = text
        hint.visibility = View.VISIBLE
    }

    protected fun hideHint() {
        if (::hint.isInitialized && hint.visibility == View.VISIBLE) hint.visibility = View.GONE
    }

    // ---------- 翻页 ----------

    private fun forward() {
        if (menuVisible) { hideMenu(); return }
        hideHint()
        nextPage()
    }

    private fun backward() {
        if (menuVisible) { hideMenu(); return }
        hideHint()
        prevPage()
    }

    /** 每次成功翻页后调用 */
    protected fun turned() {
        endArmed = false
        startArmed = false
        turns++
        val n = prefs.fullRefreshEvery
        if (n > 0 && turns % n == 0) flashRefresh()
        updateInfo()
        savePosition()
    }

    protected fun flashRefresh() {
        flash.visibility = View.VISIBLE
        flash.postDelayed({ flash.visibility = View.GONE }, 120)
    }

    protected fun reachedEnd() {
        val next = sibling(+1)
        when {
            next == null -> showHint(getString(R.string.reader_last_page))
            endArmed -> openChapter(next)
            else -> {
                endArmed = true
                showHint(getString(R.string.reader_end_of_chapter, next.title(x)))
            }
        }
    }

    protected fun reachedStart() {
        val prev = sibling(-1)
        when {
            prev == null -> showHint(getString(R.string.reader_first_page))
            startArmed -> openChapter(prev)
            else -> {
                startArmed = true
                showHint(getString(R.string.reader_start_of_chapter, prev.title(x)))
            }
        }
    }

    private fun sibling(delta: Int): LocalChapter? {
        val list = siblings ?: app.storage.listChapters(chapter.serverKey, chapter.seriesId).also { siblings = it }
        val i = list.indexOfFirst { it.chapterId == chapter.chapterId }
        if (i < 0) return null
        return list.getOrNull(i + delta)
    }

    private fun openChapter(c: LocalChapter) {
        savePosition()
        Readers.open(this, c)
        finish()
    }

    // ---------- 触摸 ----------

    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val longPress = Runnable {
        longFired = true
        showMenu()
    }

    protected val touch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longFired = false
                v.postDelayed(longPress, 650)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) v.removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPress)
                if (!longFired) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (prefs.swipe && abs(dx) > dp(48) && abs(dx) > abs(dy) * 1.5f) {
                        // 左滑 = 下一页（RTL 漫画相反）
                        if ((dx < 0) != rtl) forward() else backward()
                    } else if (abs(dx) < slop * 2 && abs(dy) < slop * 2) {
                        tap(e.x, v.width)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
        }
        true
    }

    private fun tap(x: Float, w: Int) {
        if (menuVisible) { hideMenu(); return }
        val third = prefs.tapMode == 0
        val left = if (third) x < w / 3f else x < w / 2f
        val right = if (third) x > w * 2f / 3f else x >= w / 2f
        when {
            left -> if (rtl) forward() else backward()
            right -> if (rtl) backward() else forward()
            else -> showMenu()
        }
    }

    // ---------- 按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (prefs.volumeKeys) {
                if (prefs.swapVolume) backward() else forward(); return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (prefs.volumeKeys) {
                if (prefs.swapVolume) forward() else backward(); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> { forward(); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> { backward(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (rtl) backward() else forward(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (rtl) forward() else backward(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (menuVisible) hideMenu() else showMenu(); return true
            }
            KeyEvent.KEYCODE_BACK -> if (menuVisible) { hideMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------- 全屏 ----------

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        if (::chapter.isInitialized) savePosition()
    }

    override fun onResume() {
        super.onResume()
        if (recreating || !::chapter.isInitialized) return
        // 从设置页返回：状态栏设置变了就重新应用，否则只刷新一下状态栏的时间、电量
        val resized = if (sbKey() != sbApplied) applyStatusBar() else { updateInfo(); false }
        // 放到消息队列里：等这次恢复流程（含子类 onResume 的其余部分）走完再重新排版
        rootFrame.post { if (!isFinishing && !isDestroyed) applyChangedPrefs(resized) }
    }
}

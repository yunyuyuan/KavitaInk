package net.yunyuyuan.kavitaink.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import net.yunyuyuan.kavitaink.StorageAccess
import net.yunyuyuan.kavitaink.App
import net.yunyuyuan.kavitaink.Lang
import net.yunyuyuan.kavitaink.Prefs
import net.yunyuyuan.kavitaink.R
import net.yunyuyuan.kavitaink.core.Texts
import net.yunyuyuan.kavitaink.texts
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Exec {
    val io: ExecutorService = Executors.newFixedThreadPool(2)
}

/** 设置里的屏幕方向 → Activity 的 requestedOrientation */
fun orientationFlag(v: Int): Int = when (v) {
    1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    4 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/** 屏幕方向设置值的显示名称（设置页和阅读菜单共用） */
fun android.content.Context.orientationName(v: Int): String = getString(
    when (v) {
        1 -> R.string.orient_portrait
        2 -> R.string.orient_landscape
        3 -> R.string.orient_portrait_reverse
        4 -> R.string.orient_landscape_reverse
        else -> R.string.orient_system
    }
)

/** 小说字体设置值的显示名称 */
fun android.content.Context.fontName(id: String): String = when (id) {
    Prefs.FONT_DEFAULT -> getString(R.string.font_book_default)
    Prefs.FONT_SERIF -> getString(R.string.font_serif)
    Prefs.FONT_SANS -> getString(R.string.font_sans)
    else -> App.I.fonts.get(id)?.name ?: getString(R.string.font_book_default)
}

abstract class BaseActivity : Activity() {

    protected val app: App get() = App.I
    protected val prefs: Prefs get() = App.I.prefs

    /** 当前语言的文案 */
    protected val x: Texts by lazy { texts() }

    /** 若设置，则音量键 / 翻页键用来整屏翻列表 */
    protected var pagedList: ListView? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation()
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时立即生效
        applyOrientation()
        // 从系统授权页回来：已授权就接着做刚才被拦下的操作（例如下载）
        val pending = afterStorageGranted
        if (pending != null && StorageAccess.granted(this)) {
            afterStorageGranted = null
            pending()
        }
    }

    private var afterStorageGranted: (() -> Unit)? = null

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != StorageAccess.REQUEST_CODE || StorageAccess.granted(this)) return  // 已授权：onResume 里接着做
        if (!shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // 「拒绝且不再询问」：系统不会再弹窗，打开应用详情页让用户手动允许，回来后自动继续
            android.widget.Toast.makeText(this, StorageAccess.deniedText(), android.widget.Toast.LENGTH_LONG).show()
            StorageAccess.openAppDetails(this)
        } else {
            afterStorageGranted = null
            android.widget.Toast.makeText(this, StorageAccess.deniedText(), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 需要写下载目录的操作：已授权直接执行；否则先说明用途，再打开系统授权页，授权后自动继续 */
    fun withStorageAccess(action: () -> Unit) {
        if (StorageAccess.granted(this)) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_title)
            .setMessage(getString(R.string.perm_msg, app.storage.contentRoot.absolutePath) + "\n\n" + getString(StorageAccess.stepText()))
            .setPositiveButton(R.string.perm_go) { _, _ ->
                afterStorageGranted = action
                StorageAccess.request(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    protected fun applyOrientation() {
        val want = orientationFlag(prefs.orientation)
        if (requestedOrientation != want) requestedOrientation = want
    }

    @Suppress("DEPRECATION")
    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    fun <T> bg(work: () -> T, done: (T?, Throwable?) -> Unit) {
        Exec.io.execute {
            val r = runCatching(work)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) done(r.getOrNull(), r.exceptionOrNull())
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val lv = pagedList
        if (lv != null) {
            val vol = prefs.volumeKeys
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN -> { Pager.down(lv); return true }
                KeyEvent.KEYCODE_PAGE_UP -> { Pager.up(lv); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (vol) {
                    if (prefs.swapVolume) Pager.up(lv) else Pager.down(lv); return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> if (vol) {
                    if (prefs.swapVolume) Pager.down(lv) else Pager.up(lv); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pagedList != null && prefs.volumeKeys &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 顶部标题栏：返回 + 标题 + 操作按钮。返回 (标题栏, 操作按钮列表) */
    fun header(title: String, back: Boolean, actions: List<Pair<String, () -> Unit>> = emptyList()): Pair<View, List<Button>> {
        val wrap = vbox()
        val backBtn = if (back) button(getString(R.string.back)) { onBackPressed() } else null
        val t = label(title, 20f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(if (back) dp(2) else dp(8), 0, dp(4), 0)
        }
        val btns = actions.map { (name, fn) -> button(name, fn) }
        // 按钮放不下时自动换到第二行（德语、俄语等文案较长）
        val bar = HeaderBar(this, backBtn, t, btns)
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))
        bar.minimumHeight = dp(52)
        wrap.addView(bar, lp(MATCH, WRAP))
        wrap.addView(hline(2))
        return wrap to btns
    }

    fun statusLine(): TextView = label("", 14f, color = 0xFF222222.toInt()).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    fun confirm(title: String, msg: String, ok: String = getString(R.string.ok), onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun choose(title: String, labels: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun emptyView(text: String): TextView = label(text, 16f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(48), dp(24), dp(48))
        setBackgroundColor(Color.WHITE)
    }

    fun root(): LinearLayout = vbox().apply {
        isFocusableInTouchMode = true
    }
}

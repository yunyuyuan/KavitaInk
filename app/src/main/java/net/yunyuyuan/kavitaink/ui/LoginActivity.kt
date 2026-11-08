package net.yunyuyuan.kavitaink.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import net.yunyuyuan.kavitaink.R
import net.yunyuyuan.kavitaink.core.ApiException
import net.yunyuyuan.kavitaink.core.error
import net.yunyuyuan.kavitaink.core.KavitaApi

class LoginActivity : BaseActivity() {

    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var key: EditText
    private lateinit var status: TextView
    private lateinit var go: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = root()
        // 顶栏也放一个「连接」，避免软键盘弹出时挡住底部按钮
        root.addView(header(getString(R.string.login_title), back = true, actions = listOf(getString(R.string.login_connect) to { connect() })).first)
        val scroll = ScrollView(this).apply { isVerticalFadingEdgeEnabled = false; overScrollMode = ScrollView.OVER_SCROLL_NEVER }
        val form = vbox().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }

        form.addView(label(getString(R.string.login_server), 15f, bold = true))
        url = field(getString(R.string.login_server_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        url.setText(prefs.serverUrl)
        form.addView(url)

        form.addView(gap())
        form.addView(label(getString(R.string.login_way1), 15f, bold = true))
        form.addView(label(getString(R.string.login_way1_note), 13f))
        user = field(getString(R.string.login_user), InputType.TYPE_CLASS_TEXT)
        user.setText(prefs.username)
        form.addView(user)
        pass = field(getString(R.string.login_pass), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pass.imeOptions = EditorInfo.IME_ACTION_GO
        pass.setOnEditorActionListener { _, _, _ -> connect(); true }
        form.addView(pass)

        form.addView(gap())
        form.addView(label(getString(R.string.login_way2), 15f, bold = true))
        form.addView(label(getString(R.string.login_way2_note), 13f))
        key = field(getString(R.string.login_key_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        key.imeOptions = EditorInfo.IME_ACTION_GO
        key.setOnEditorActionListener { _, _, _ -> connect(); true }
        form.addView(key)

        form.addView(gap())
        go = button(getString(R.string.login_connect)) { connect() }
        form.addView(go, lp(MATCH, WRAP))
        status = label("", 15f).apply { setPadding(0, dp(12), 0, dp(12)) }
        form.addView(status)
        form.addView(
            label(getString(R.string.login_notes), 13f, color = 0xFF333333.toInt())
        )
        scroll.addView(form)
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
        root.requestFocus()
    }

    private fun gap() = android.view.View(this).apply { layoutParams = lp(MATCH, dp(14)) }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        isSingleLine = true
        textSize = 16f
        setTextColor(0xFF000000.toInt())
        background = resources.getDrawable(R.drawable.btn_bg, theme)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(6) }
    }

    private fun connect() {
        if (!go.isEnabled) return
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        val name = user.text.toString().trim()
        val pw = pass.text.toString()
        if (u.isBlank() && KavitaApi.keyFromOpdsUrl(k) == null) {
            status.text = getString(R.string.login_need_server)
            return
        }
        status.text = getString(R.string.login_connecting)
        go.isEnabled = false
        bg({
            val keyInKeyField = KavitaApi.keyFromOpdsUrl(k)
            val keyInUrlField = KavitaApi.keyFromOpdsUrl(u)
            val base = if (u.isBlank()) k else u
            val api = when {
                keyInKeyField != null -> KavitaApi(if (u.isBlank()) k else u, keyInKeyField)
                k.isNotBlank() -> KavitaApi(base, k)
                keyInUrlField != null -> KavitaApi(u, keyInUrlField)
                name.isNotBlank() -> KavitaApi.login(u, name, pw)
                else -> throw ApiException(0, R.string.err_need_credentials)
            }
            val libs = api.libraries()
            val version = runCatching { api.version() }.getOrDefault("")
            Triple(api, libs.size, version)
        }) { r, err ->
            go.isEnabled = true
            if (r == null) {
                status.text = getString(R.string.login_failed, x.error(err))
                return@bg
            }
            val (api, n, version) = r
            prefs.serverUrl = api.base
            prefs.apiKey = api.apiKey
            prefs.username = name
            status.text = resources.getQuantityString(R.plurals.login_ok, n, version, n)
            startActivity(Intent(this, LibrariesActivity::class.java))
            finish()
        }
    }
}

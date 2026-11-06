package com.manus.kavitaink.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.manus.kavitaink.core.ApiException
import com.manus.kavitaink.core.KavitaApi

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
        root.addView(header("连接 Kavita", back = true, actions = listOf("连接" to { connect() })).first)
        val scroll = ScrollView(this).apply { isVerticalFadingEdgeEnabled = false; overScrollMode = ScrollView.OVER_SCROLL_NEVER }
        val form = vbox().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }

        form.addView(label("服务器地址", 15f, bold = true))
        url = field("例如 http://192.168.1.10:5000", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        url.setText(prefs.serverUrl)
        form.addView(url)

        form.addView(gap())
        form.addView(label("方式一：用户名 + 密码", 15f, bold = true))
        form.addView(label("密码只用于换取 Auth Key，不会保存在设备上", 13f))
        user = field("用户名", InputType.TYPE_CLASS_TEXT)
        user.setText(prefs.username)
        form.addView(user)
        pass = field("密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pass.imeOptions = EditorInfo.IME_ACTION_GO
        pass.setOnEditorActionListener { _, _, _ -> connect(); true }
        form.addView(pass)

        form.addView(gap())
        form.addView(label("方式二：Auth Key 或 OPDS 链接", 15f, bold = true))
        form.addView(label("Kavita 网页 → 设置 → 账户 → Auth Keys / OPDS。填写后可不填用户名密码", 13f))
        key = field("Auth Key 或 http://…/api/opds/…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        key.imeOptions = EditorInfo.IME_ACTION_GO
        key.setOnEditorActionListener { _, _, _ -> connect(); true }
        form.addView(key)

        form.addView(gap())
        go = button("连接") { connect() }
        form.addView(go, lp(MATCH, WRAP))
        status = label("", 15f).apply { setPadding(0, dp(12), 0, dp(12)) }
        form.addView(status)
        form.addView(
            label(
                "说明：\n• 下载需要该账号在 Kavita 中拥有「Download」角色\n" +
                    "• 局域网 http 地址可直接使用；自签名 https 证书需先在系统中安装\n" +
                    "• 本应用只负责下载和离线阅读，不会上传或同步阅读进度",
                13f, color = 0xFF333333.toInt()
            )
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
        background = resources.getDrawable(com.manus.kavitaink.R.drawable.btn_bg, theme)
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
            status.text = "请填写服务器地址"
            return
        }
        status.text = "正在连接…"
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
                else -> throw ApiException(0, "请填写用户名和密码，或填写 Auth Key")
            }
            val libs = api.libraries()
            val version = runCatching { api.version() }.getOrDefault("")
            Triple(api, libs.size, version)
        }) { r, err ->
            go.isEnabled = true
            if (r == null) {
                status.text = "连接失败：" + KavitaApi.friendlyError(err)
                return@bg
            }
            val (api, n, version) = r
            prefs.serverUrl = api.base
            prefs.apiKey = api.apiKey
            prefs.username = name
            status.text = "连接成功：Kavita $version，共 $n 个书库"
            startActivity(Intent(this, LibrariesActivity::class.java))
            finish()
        }
    }
}

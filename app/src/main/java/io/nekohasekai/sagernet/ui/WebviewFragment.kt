package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment must have a no-argument public constructor, otherwise it will crash during data restoration

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {
    private val defaultPanelUrl = "http://127.0.0.1:9090/ui"

    lateinit var mWebView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.apply {
    // The Clash/yacd dashboard is a JS SPA talking to the local Clash API, so JS
    // and DOM storage are required. Everything else is locked down: the dashboard
    // (a user-editable URL) must never be able to read the device filesystem or
    // content providers, escalate from file:// origins, or downgrade to cleartext
    // resources on an https page.
    javaScriptEnabled = true
    domStorageEnabled = true

    // 嚴格限制檔案與 Content Provider 存取
    allowFileAccess = false
    allowContentAccess = false
    databaseEnabled = false

    @Suppress("DEPRECATION")
    allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    allowUniversalAccessFromFileURLs = false

    // 混合內容安全性與彈出視窗控制
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)

    // 啟用 Google 安全瀏覽功能（API 26+）
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        safeBrowsingEnabled = true
    }
}
        mWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        loadSafeUrl(dashboardUrl())
    }

    private fun dashboardUrl(): String {
        val base = DataStore.yacdURL
        val uri = try {
            base.toUri()
        } catch (e: Exception) {
            return base
        }
        // Only inject the token into the local controller's own UI; never append it
        // to a user-configured remote dashboard URL. Match host + port exactly (a
        // prefix check would also match e.g. 127.0.0.1:90909).
        if (!(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 9090)) return base
        if (uri.getQueryParameter("secret") != null) return base
        // Build the query via Uri so the params land in the query component, not
        // inside a #fragment (appending a raw "?..." after a fragment hides them).
        return uri.buildUpon()
            .appendQueryParameter("hostname", "127.0.0.1")
            .appendQueryParameter("port", "9090")
            .appendQueryParameter("secret", DataStore.clashApiSecret)
            .build()
            .toString()
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                val candidateUrl = view.text.toString()
                        val safeUrl = normalizePanelUrl(candidateUrl)
                        if (safeUrl == null) {
                            Toast.makeText(
                                requireContext(), R.string.invalid_panel_url, Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                        // 1. 儲存經過格式化與驗證後的安全網址
                        DataStore.yacdURL = safeUrl

                        // 2. 透過 dashboardUrl() 判斷是否需要注入本地 Secret，再經由 loadSafeUrl 載入
                        loadSafeUrl(dashboardUrl())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                mWebView.onPause()
                mWebView.removeAllViews()
                mWebView.destroy()
            }
        }
        return true
    }

    private fun loadSafeUrl(url: String) {
        val safeUrl = normalizePanelUrl(url) ?: defaultPanelUrl
        DataStore.yacdURL = safeUrl
        mWebView.loadUrl(safeUrl)
    }

    private fun normalizePanelUrl(rawUrl: String): String? {
        val parsed = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (scheme == "http" && !isLoopbackHost(parsed.host)) return null
        return parsed.toString()
    }

    private fun isLoopbackHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase() ?: return false
        return normalized == "127.0.0.1" || normalized == "localhost" || normalized == "[::1]" || normalized == "::1"
    }
}

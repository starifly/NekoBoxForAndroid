package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
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
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // No automatic JS-initiated window.open popups.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        mWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        mWebView.loadUrl(dashboardUrl())
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
                        DataStore.yacdURL = view.text.toString()
                        mWebView.loadUrl(dashboardUrl())
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
}

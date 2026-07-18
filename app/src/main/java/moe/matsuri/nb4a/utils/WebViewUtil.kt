package moe.matsuri.nb4a.utils

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import io.nekohasekai.sagernet.ktx.Logs
import java.io.ByteArrayInputStream
import java.io.InputStream

fun onReceivedError(
        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
    ) {
        if (error != null) {
            Logs.e("WebView error description: ${error.description}")
        }
        Logs.e("WebView error: $error")
    }

    fun interceptRequest(
        res: (String) -> InputStream?,
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse {
        val path = request?.url?.path ?: "404"
        val input = res(path)
        var mime = "text/plain"
        if (path.endsWith(".js")) mime = "application/javascript"
        if (path.endsWith(".html")) mime = "text/html"
        return if (input != null) {
            WebResourceResponse(mime, "UTF-8", input)
        } else {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream("".toByteArray()),
            )
        }
    }
}

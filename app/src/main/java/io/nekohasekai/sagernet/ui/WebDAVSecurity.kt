package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Security policy for WebDAV endpoints.
 *
 * A WebDAV backup contains every saved profile, including all proxy secrets, and
 * the credentials are sent via HTTP Basic auth. Allowing a plain `http://` endpoint
 * would transmit both the credentials and the secret-bearing backup in cleartext,
 * so only TLS (`https://`) endpoints are accepted.
 */
object WebDAVSecurity {

    /**
     * Validates and parses a user-provided WebDAV server URL.
     *
     * @return the parsed [HttpUrl] when the URL is a well-formed `https://` endpoint.
     * @throws Exception with a localized message when the scheme is not `https`
     *   or the URL is malformed. The message intentionally contains no credentials.
     */
    fun requireSecureUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw Exception(SagerNet.application.getString(R.string.webdav_insecure_url))
        if (!url.isHttps) {
            throw Exception(SagerNet.application.getString(R.string.webdav_insecure_url))
        }
        return url
    }
}

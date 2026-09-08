package io.nekohasekai.sagernet.bg

import com.google.gson.JsonParser

internal fun tunMtu(optionsJson: String, fallback: Int): Int {
    // This is sing-tun.Options marshaled by Go, not the inbound config JSON.
    return JsonParser.parseString(optionsJson).asJsonObject.get("MTU")?.asInt ?: fallback
}

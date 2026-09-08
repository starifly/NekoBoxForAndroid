package io.nekohasekai.sagernet.route

import com.google.gson.Gson

data class RouterFilterConfig(
    val includeRegex: String = "",
    val excludeRegex: String = "",
    val testUrl: String = DEFAULT_TEST_URL,
    val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    val toleranceMs: Int = DEFAULT_TOLERANCE_MS,
) {
    fun validate(): RouterFilterValidation = RouterFilterValidation(
        include = includeRegex.compileIfPresent(RouterFilterException.Field.INCLUDE),
        exclude = excludeRegex.compileIfPresent(RouterFilterException.Field.EXCLUDE),
    )

    fun toJson(): String = Gson().toJson(this)

    companion object {
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_INTERVAL_SECONDS = 300L
        const val DEFAULT_TOLERANCE_MS = 50

        fun fromJson(value: String): RouterFilterConfig {
            if (value.isBlank()) return RouterFilterConfig()
            return Gson().fromJson(value, RouterFilterConfig::class.java).let { parsed ->
                parsed.copy(
                    testUrl = parsed.testUrl.takeIf(String::isNotBlank) ?: DEFAULT_TEST_URL,
                    intervalSeconds = parsed.intervalSeconds.takeIf { it > 0 } ?: DEFAULT_INTERVAL_SECONDS,
                    toleranceMs = parsed.toleranceMs.takeIf { it >= 0 } ?: DEFAULT_TOLERANCE_MS,
                )
            }
        }
    }
}

data class RouterFilterValidation(
    val include: Regex?,
    val exclude: Regex?,
)

class RouterFilterException(
    val field: Field,
    cause: Throwable,
) : IllegalArgumentException(cause.message, cause) {
    enum class Field {
        INCLUDE,
        EXCLUDE,
    }
}

private fun String.compileIfPresent(field: RouterFilterException.Field): Regex? {
    if (isBlank()) return null
    return try {
        toRegex(RegexOption.IGNORE_CASE)
    } catch (error: IllegalArgumentException) {
        throw RouterFilterException(field, error)
    }
}

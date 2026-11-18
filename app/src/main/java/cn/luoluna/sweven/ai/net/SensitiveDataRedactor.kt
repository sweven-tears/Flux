package cn.luoluna.sweven.ai.net

/**
 * JVM-only sanitization for untrusted upstream diagnostics and network-log metadata.
 * Redaction happens before truncation so a clipped credential cannot leave a usable prefix.
 */
object SensitiveDataRedactor {
    private const val MAX_UPSTREAM_ERROR_LENGTH = 300
    private const val REDACTED = "***"

    private val skToken = Regex("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9_./~+\\-=]{8,}")
    private val sensitiveJsonField = Regex(
        "(?i)([\\\"](?:api[_-]?key|x-api-key|token|access[_-]?token|refresh[_-]?token|authorization|secret|password|credential|device[-_]?token)[\\\"]\\s*:\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|[^,}\\s]+)"
    )
    private val sensitiveAssignment = Regex(
        "(?i)(\\b(?:api[_-]?key|x-api-key|token|access[_-]?token|refresh[_-]?token|authorization|secret|password|credential|device[-_]?token)\\b\\s*[=:]\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|'[^']*'|[^&;,\\s}]+)"
    )
    private val longAlphaNumeric = Regex("\\b[A-Za-z0-9]{32,}\\b")
    private val urlQueryPair = Regex("([?&])([^=&#]+)=([^&#]*)")
    private val urlUserInfo = Regex("(?i)(https?://)[^/@\\s]+@")
    private val sensitiveQueryName = Regex("(?i)(?:key|token|auth|authorization|signature|secret|credential|password|api[-_]?key)")

    /** Redact secrets and cap the resulting diagnostic at 300 characters. */
    fun redactUpstreamError(body: String?): String = redactText(body, maxLength = MAX_UPSTREAM_ERROR_LENGTH)

    /** Redact arbitrary log text without imposing the upstream-error length cap. */
    fun redactText(value: String?, knownSecrets: Iterable<String> = emptyList(), maxLength: Int = Int.MAX_VALUE): String {
        val input = value?.takeUnless { it.isBlank() } ?: return ""
        var result: String = input
        // Explicit secrets known at a request boundary are handled first, including short test keys.
        knownSecrets.asSequence().filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { secret ->
            result = result.replace(secret, REDACTED, ignoreCase = false)
        }
        result = urlUserInfo.replace(result) { "${it.groupValues[1]}$REDACTED@" }
        result = urlQueryPair.replace(result) { match ->
            val separator = match.groupValues[1]
            val name = match.groupValues[2]
            if (sensitiveQueryName.containsMatchIn(name)) "$separator$name=$REDACTED" else match.value
        }
        result = sensitiveJsonField.replace(result) { "${it.groupValues[1]}\"$REDACTED\"" }
        result = sensitiveAssignment.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = skToken.replace(result, "sk-$REDACTED")
        result = bearer.replace(result) { "Bearer $REDACTED" }
        result = longAlphaNumeric.replace(result, REDACTED)
        return if (result.length > maxLength) result.take(maxLength) else result
    }

    /** URL logs retain useful paths and ordinary parameters, but never query credentials. */
    fun redactUrl(url: String): String = redactText(url)

    /** Header logs retain ordinary diagnostic headers but redact credential-bearing headers. */
    fun redactHeader(name: String, value: String): String {
        val sensitiveHeader = Regex("(?i)(?:authorization|proxy-authorization|cookie|set-cookie|api[-_]?key|token|secret|credential|password|signature|device[-_]?token)")
        if (sensitiveHeader.containsMatchIn(name)) return REDACTED
        return redactText(value)
    }
}

/** Shared convenience entrypoint for network/error paths. */
internal fun redactUpstreamError(body: String?): String = SensitiveDataRedactor.redactUpstreamError(body)

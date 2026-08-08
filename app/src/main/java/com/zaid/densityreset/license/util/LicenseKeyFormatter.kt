package com.zaid.densityreset.license.util

object LicenseKeyFormatter {
    private const val PREFIX = "DR"
    private const val BODY_LENGTH = 16
    private val normalizedPattern =
        Regex("^DR-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    fun normalize(raw: String): String {
        val compact = raw
            .trim()
            .uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' }
        val body = if (compact.startsWith(PREFIX)) {
            compact.drop(PREFIX.length)
        } else {
            compact
        }.take(BODY_LENGTH)

        if (body.isEmpty()) return "DR-"
        return buildString {
            append("DR-")
            body.chunked(4).forEachIndexed { index, chunk ->
                if (index > 0) append('-')
                append(chunk)
            }
        }
    }

    fun formatForInput(raw: String): String = normalize(raw)

    fun isValid(raw: String): Boolean = normalizedPattern.matches(normalize(raw))
}

package com.wyspr.feature.messaging.links

import android.util.Patterns

object LinkDetector {

    private val URL_PATTERN = Patterns.WEB_URL

    fun findUrls(text: String): List<LinkMatch> {
        val matcher = URL_PATTERN.matcher(text)
        return buildList {
            while (matcher.find()) {
                val url = matcher.group()
                if (url != null && (url.contains('.') || url.startsWith("http"))) {
                    add(LinkMatch(url = url, start = matcher.start(), end = matcher.end()))
                }
            }
        }
    }

    fun hasUrl(text: String): Boolean = findUrls(text).isNotEmpty()

    data class LinkMatch(
        val url: String,
        val start: Int,
        val end: Int,
    ) {
        val fullUrl: String
            get() = if (url.startsWith("http://") || url.startsWith("https://")) url
            else "https://$url"

        val displayHost: String
            get() {
                val stripped = fullUrl
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
                val slash = stripped.indexOf('/')
                return if (slash > 0) stripped.substring(0, slash) else stripped
            }
    }
}

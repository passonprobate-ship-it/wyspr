package com.wyspr.feature.messaging.disappear

object DisappearPayload {

    private const val PREFIX = "wyspr:disappear:"

    fun encode(seconds: Long): String = "$PREFIX$seconds"

    fun decode(body: String): Long? {
        if (!body.startsWith(PREFIX)) return null
        return body.substring(PREFIX.length).toLongOrNull()
    }

    fun isDisappear(body: String): Boolean = body.startsWith(PREFIX)

    val TIMER_OPTIONS: List<Pair<String, Long?>> = listOf(
        "Off" to null,
        "5 minutes" to 300L,
        "1 hour" to 3_600L,
        "24 hours" to 86_400L,
        "7 days" to 604_800L,
    )

    fun labelFor(seconds: Long?): String = TIMER_OPTIONS.firstOrNull { it.second == seconds }?.first ?: "Off"
}

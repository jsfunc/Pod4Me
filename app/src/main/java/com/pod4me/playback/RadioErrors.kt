package com.pod4me.playback

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

internal class RadioHttpException(val code: Int) : IOException("Radio server returned HTTP $code") {
    val permanent get() = code in 400..499 && code !in setOf(408, 429)
}

internal fun radioFailureReason(error: Exception): String = when (error) {
    is UnknownHostException -> "Cannot find the station's server. Check your connection or stream URL"
    is InterruptedIOException -> "The station took too long to respond"
    is ConnectException -> "Cannot connect to the station. Check your connection"
    is RadioHttpException -> when (error.code) {
        401, 403 -> "The station refused access (HTTP ${error.code})"
        404, 410 -> "The stream address is unavailable (HTTP ${error.code})"
        408, 504 -> "The station took too long to respond (HTTP ${error.code})"
        429 -> "The station is busy (HTTP 429). Try again later"
        in 500..599 -> "The station's server is unavailable (HTTP ${error.code})"
        else -> "The station returned HTTP ${error.code}"
    }
    else -> error.message?.takeIf { it.isNotBlank() } ?: "The connection failed"
}

internal fun radioStartupMessage(station: String, reason: String): String =
    "Could not play $station. ${reason.trim().trimEnd('.')}. Tap Play to retry."

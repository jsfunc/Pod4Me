package com.pod4me.playback

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class RadioErrorsTest {
    @Test fun knownNetworkFailuresHavePlainExplanationsAndUnknownFailuresKeepDetails() {
        assertEquals("Cannot find the station's server. Check your connection or stream URL",
            radioFailureReason(UnknownHostException("Unable to resolve host internal details")))
        for (error in listOf(SocketTimeoutException("timeout"), InterruptedIOException("timeout")))
            assertEquals("The station took too long to respond", radioFailureReason(error))
        assertEquals("Cannot connect to the station. Check your connection", radioFailureReason(ConnectException("ECONNREFUSED")))
        assertEquals("Unsupported radio codec", radioFailureReason(IOException("Unsupported radio codec")))
        assertEquals("The connection failed", radioFailureReason(IOException()))
    }

    @Test fun httpFailuresKeepCodesAndTheExistingRetryClassification() {
        val expected = mapOf(403 to "The station refused access (HTTP 403)",
            404 to "The stream address is unavailable (HTTP 404)",
            408 to "The station took too long to respond (HTTP 408)",
            429 to "The station is busy (HTTP 429). Try again later",
            503 to "The station's server is unavailable (HTTP 503)",
            504 to "The station took too long to respond (HTTP 504)")
        for ((code, reason) in expected) {
            val error = RadioHttpException(code)
            assertEquals(reason, radioFailureReason(error))
            assertEquals(code == 403 || code == 404, error.permanent)
        }
    }

    @Test fun startupMessageDoesNotRepeatAdviceOrPunctuation() {
        assertEquals("Could not play Test. This address is a web page. Use the station's direct audio URL. Tap Play to retry.",
            radioStartupMessage("Test", "This address is a web page. Use the station's direct audio URL"))
        assertEquals("Could not play Test. Offline. Tap Play to retry.", radioStartupMessage("Test", "Offline."))
    }
}

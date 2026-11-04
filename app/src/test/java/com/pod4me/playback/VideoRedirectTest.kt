package com.pod4me.playback

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoRedirectTest {
    @Test fun videoHttpUpgradeFollowsRedirectAndRetainsRangeRequest() {
        // Dedicated localhost-only certificate; trust customization is confined to this test.
        val keys = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/radio/localhost-test.p12")!!.use { keys.load(it, "testing-only".toCharArray()) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "testing-only".toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val ssl = SSLContext.getInstance("TLS", "SunJSSE").apply { init(km.keyManagers, tm.trustManagers, null) }
        val secure = ssl.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val plain = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newFixedThreadPool(2) { Thread(it, "redirect-test").apply { isDaemon = true } }
        val secureResult = executor.submit<String?> {
            secure.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 2-4/5\r\nContent-Length: 3\r\nConnection: close\r\n\r\n".toByteArray())
                    write(byteArrayOf(3, 4, 5)); flush()
                }
                range
            }
        }
        executor.submit {
            plain.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 302 Found\r\nLocation: https://localhost:${secure.localPort}/video\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                }
            }
        }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val source = PlaybackService.videoHttpFactory().createDataSource()
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            source.open(DataSpec.Builder().setUri(Uri.parse("http://localhost:${plain.localPort}/video"))
                .setPosition(2).setLength(3).build())
            val result = ByteArray(3)
            var count = 0
            while (count < result.size) {
                val read = source.read(result, count, result.size - count)
                assertTrue(read > 0); count += read
            }
            assertArrayEquals(byteArrayOf(3, 4, 5), result)
            assertEquals("bytes=2-4", secureResult.get(5, TimeUnit.SECONDS))
            assertEquals("https", source.uri!!.scheme)
        } catch (e: Exception) {
            if (secureResult.isDone) secureResult.get(1, TimeUnit.SECONDS)
            throw e
        } finally {
            source.close(); plain.close(); secure.close(); executor.shutdownNow()
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }
}

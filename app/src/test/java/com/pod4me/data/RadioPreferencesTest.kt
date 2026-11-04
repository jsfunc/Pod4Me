package com.pod4me.data

import com.pod4me.PodApplication
import com.pod4me.domain.RadioCatalogue
import com.pod4me.domain.RadioStation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PodApplication::class)
class RadioPreferencesTest {
    @Test fun radioOrderSurvivesReopeningAndNewStationsAppend() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val prefs = RadioPreferences(app)
        val original = prefs.read()
        val order = listOf(original[2], original[0], original[1])
        prefs.reorder(order.map { it.id })
        assertEquals(order, RadioPreferences(app).read())
        prefs.subscribe("New station", "https://example.com/new.mp3")
        val reopened = RadioPreferences(app).read()
        assertEquals(order, reopened.take(3))
        assertEquals("New station", reopened.last().name)
    }

    @Test fun staleDragDoesNotRestoreRemovedStationsOrLoseNewOnes() = runBlocking {
        val prefs = RadioPreferences(RuntimeEnvironment.getApplication())
        val old = prefs.read()
        prefs.unsubscribe(old[1].id)
        prefs.subscribe("New station", "https://example.com/new.mp3")
        val added = prefs.read().last()
        prefs.reorder(listOf(old[2].id, old[1].id, "unknown", old[2].id, old[0].id))
        assertEquals(listOf(old[2], old[0], added), prefs.read())
        prefs.read().forEach { prefs.unsubscribe(it.id) }
        prefs.reorder(old.map { it.id })
        assertTrue(prefs.read().isEmpty())
    }

    @Test fun defaultsAreSeededOnceAndRemovingAllSurvivesReopening() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val prefs = RadioPreferences(app)
        assertEquals(listOf("France Info", "France Culture", "Europe 2"), prefs.read().map { it.name })
        prefs.read().forEach { prefs.unsubscribe(it.id) }
        assertTrue(RadioPreferences(app).read().isEmpty())
    }

    @Test fun concurrentSubscriptionsAreDurableAndDuplicateStreamsAreIgnored() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val prefs = RadioPreferences(app)
        (1..10).map { index -> async { prefs.subscribe("Radio $index", "https://example.com/$index.mp3") } }.awaitAll()
        prefs.subscribe("Another title", " https://example.com/1.mp3 ")
        val restored = RadioPreferences(app).read()
        assertEquals(13, restored.size)
        assertEquals("Radio 1", restored.single { it.url == "https://example.com/1.mp3" }.name)
        assertEquals(prefs.state.value, restored)
    }

    @Test fun corruptRowDoesNotHideGoodStationsAndOriginalSurvivesEdits() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val saved = """[{"name":"Good","url":"https://example.com/live"},{"name":"Bad","url":"file:///bad"}]"""
        val disk = app.getSharedPreferences("live_radios", 0)
        disk.edit().putString("stations", saved).commit()
        val messages = mutableListOf<String>()
        val prefs = RadioPreferences(app) { messages.add(it) }
        assertEquals(listOf("Good"), prefs.read().map { it.name })
        prefs.read()
        assertEquals(1, messages.size)
        prefs.subscribe("New", "https://example.com/new")
        assertEquals(saved, disk.getString("stations_recovery", null))
        assertEquals(2, RadioPreferences(app).read().size)
    }

    @Test fun invalidInputsDoNotChangeSubscriptions() = runBlocking {
        val prefs = RadioPreferences(RuntimeEnvironment.getApplication())
        val before = prefs.read()
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "https://user:password@example.com/live", "not a URL", "https://example.com/live#section")) {
            try { prefs.subscribe("Invalid", url); fail(url) } catch (_: IllegalArgumentException) {}
        }
        try { prefs.subscribe("  ", "https://example.com/live"); fail("Blank name") } catch (_: IllegalArgumentException) {}
        assertEquals(before, prefs.read())
    }

    @Test fun stationIdentityPreservesStreamQueriesAndAcceptsHls() {
        val a = RadioStation.create(" First ", " https://example.com/live.m3u8?station=1 ")
        assertEquals("First", a.name)
        assertEquals(a.id, RadioStation.create("Renamed", a.url).id)
        assertNotEquals(a.id, RadioStation.create("Second", "https://example.com/live.m3u8?station=2").id)
        assertTrue(RadioStation.isRadio(a.id))
        assertEquals(RadioCatalogue.stations.size, RadioCatalogue.stations.map { it.id }.toSet().size)
    }
}

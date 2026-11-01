package com.pod4me.ui

import android.net.Uri
import com.pod4me.PodApplication
import com.pod4me.domain.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PodApplication::class)
class AppModelTest {
    @Test fun busyActionsGiveFeedbackAndKeepIncomingSuggestion() {
        val model = AppModel(RuntimeEnvironment.getApplication())
        val field = AppModel::class.java.getDeclaredField("mutableBusy").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<Boolean>).value = true
        model.importFeeds(Uri.parse("content://test/import"))
        assertTrue(model.notice.value!!.contains("Please wait"))
        model.clearNotice()
        model.receive("pod4me://episode?url=https%3A%2F%2Fexample.com%2Fa.mp3")
        val suggestion = model.suggestion.value!!
        model.acceptSuggestion(suggestion, "episode", false)
        assertEquals(suggestion, model.suggestion.value)
        assertTrue(model.notice.value!!.contains("Please wait"))
        model.player.disconnect()
    }
}

package com.pod4me.domain;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on Android itself to catch platform differences hidden by desktop JVM tests. */
@RunWith(AndroidJUnit4.class)
public class FeedListFormatDeviceTest {
    @Test public void ignoresEverythingAfterFirstSpace() {
        assertEquals(List.of("https://example.com/feed"),
            FeedListFormat.urls("https://example.com/feed [Podcast title] more words"));
    }

    @Test public void acceptsTabsNonBreakingSpacesAndEncodedSpaces() {
        assertEquals(List.of("https://example.com/a%20b", "https://example.com/c"),
            FeedListFormat.urls("\uFEFFhttps://example.com/a%20b\t[First]\r\n\u00A0https://example.com/c\u202F[Second]"));
    }

    @Test public void exportedTitlesImportAsUrlsOnly() {
        String exported = FeedListFormat.line("https://example.com/feed", "Café\nNews");
        assertEquals("https://example.com/feed [Café News]", exported);
        assertEquals(List.of("https://example.com/feed"), FeedListFormat.urls(exported));
    }
}

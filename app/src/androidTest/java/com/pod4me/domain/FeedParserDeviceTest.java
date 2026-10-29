package com.pod4me.domain;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises Android's SAX implementation, including lexical-handler DTD rejection. */
@RunWith(AndroidJUnit4.class)
public class FeedParserDeviceTest {
    @Test public void parsesStreamingFeedAndArtwork() throws Exception {
        String xml = "<rss xmlns:itunes='http://www.itunes.com/dtds/podcast-1.0.dtd'><channel><title>Feed</title><itunes:image href='/cover.jpg'/><item><guid>1</guid><title>Episode</title><enclosure url='/audio.mp3'/><description><![CDATA[<p>Notes</p>]]></description></item></channel></rss>";
        FeedParser.Feed feed = FeedParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "https://example.com/feed");
        assertEquals("Feed", feed.title());
        assertEquals("https://example.com/cover.jpg", feed.artworkUrl());
        assertEquals("<p>Notes</p>", feed.items().get(0).description());
    }

    @Test public void auditDateUrlAndNamespaceRegressionsOnAndroid() throws Exception {
        String xml = "<rss xmlns:i='http://www.itunes.com/dtds/podcast-1.0.dtd'><channel><title>Feed</title><item><i:title>Short</i:title><title>Full title</title><pubDate>Wed, 02 Oct 2002 08:00:00 PDT</pubDate><enclosure url='/a file.mp3?sig=a%2Fb'/></item></channel></rss>";
        FeedParser.Item item = FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8), "https://example.com/feed").items().get(0);
        assertEquals("Full title", item.title());
        assertEquals("https://example.com/a%20file.mp3?sig=a%2Fb", item.audioUrl());
        assertEquals(java.time.Instant.parse("2002-10-02T15:00:00Z").toEpochMilli(), item.published());
    }

    @Test public void parsesDefaultRssNamespaceWithoutMixingExtensions() throws Exception {
        String xml = "<rss xmlns='urn:publisher:rss' xmlns:x='urn:other'><channel><x:title>Wrong</x:title><title>Feed</title><image><url>/cover.png</url></image><item><x:title>Wrong</x:title><title>Episode</title><x:enclosure url='/wrong.mp3'/><enclosure url='/right.mp3'/><description>Notes</description></item></channel></rss>";
        FeedParser.Feed feed = FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8), "https://example.com/feed");
        assertEquals("Feed", feed.title());
        assertEquals("https://example.com/cover.png", feed.artworkUrl());
        assertEquals(1, feed.items().size());
        assertEquals("Episode", feed.items().get(0).title());
        assertEquals("Notes", feed.items().get(0).description());
        assertEquals("https://example.com/right.mp3", feed.items().get(0).audioUrl());
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsDtdOnAndroid() throws Exception {
        FeedParser.parse("<!DOCTYPE rss [<!ENTITY x 'unsafe'>]><rss><channel><title>&x;</title></channel></rss>".getBytes(StandardCharsets.UTF_8), "https://example.com/feed");
    }

    @Test public void importsOverEightMegabytesOnAndroid() throws Exception {
        Enumeration<InputStream> parts = new Enumeration<>() {
            int index;
            public boolean hasMoreElements() { return index <= 2201; }
            public InputStream nextElement() {
                int current = index++;
                String xml = current == 0 ? "<rss><channel><title>Large feed</title>" : current == 2201 ? "</channel></rss>" :
                    "<item><guid>" + current + "</guid><enclosure url='https://example.com/" + current + ".mp3'/><description>" + "x".repeat(4096) + "</description></item>";
                return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            }
        };
        try (InputStream input = new SequenceInputStream(parts)) {
            assertEquals(2200, FeedParser.parse(input, "https://example.com/feed").items().size());
        }
    }
}

package com.pod4me.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Runs unchanged under JUnit and the dependency-free local test launcher. */
public final class CoreChecks {
    private static int checks;
    public static void run() throws Exception {
        checks = 0;
        long now = 1_700_000_000_000L;
        equal(now + 30 * Policy.DAY, Policy.downloadExpiry(now, 30), "30 days from download");
        equal(null, Policy.downloadExpiry(now, null), "infinite expiry");
        rejects(() -> Policy.downloadExpiry(now, 0), "reject zero-day expiry");
        rejects(() -> Policy.downloadExpiry(now, -1), "reject negative expiry");
        rejects(() -> Policy.downloadExpiry(now, 36501), "reject overflowing user input");
        equal(false, Policy.isHeard(9499, 10000), "not heard below 95 percent");
        equal(true, Policy.isHeard(9500, 10000), "heard at 95 percent");
        equal(false, Policy.isHeard(100, 0), "unknown duration is not heard");
        equal(false, Policy.isHeard(100, -1), "invalid duration is not heard");
        equal(now + 7 * Policy.DAY, Policy.heardExpiry(null, now), "heard infinite episode expires in 7 days");
        equal(now + Policy.DAY, Policy.heardExpiry(now + Policy.DAY, now), "heard never extends expiry");
        equal(now + 7 * Policy.DAY, Policy.heardExpiry(now + 30 * Policy.DAY, now), "heard caps existing expiry");
        equal(now - 1, Policy.heardExpiry(now - 1, now), "already expired stays expired");
        equal(false, Policy.isExpired(now, now, true, false), "4ever survives expiry");
        equal(false, Policy.canDelete(true, false), "4ever prevents manual deletion");
        equal(false, Policy.canDelete(false, true), "active playback prevents deletion");
        equal(true, Policy.canDelete(false, false), "ordinary downloads are deletable");
        equal(false, Policy.isExpired(now, now, false, true), "active playback survives expiry");
        equal(true, Policy.isExpired(now, now, false, false), "expires at exact deadline");
        equal(false, Policy.isExpired(null, now, false, false), "no expiry means no deletion");
        equal(false, Policy.isExpired(now + 1, now, false, false), "future deadline survives");
        equal(0L, Policy.resumePosition(1000, 1000), "finished audio restarts");
        equal(950L, Policy.resumePosition(950, 1000), "nearly finished audio resumes");
        equal(0L, Policy.resumePosition(-20, 1000), "negative position clamps");

        Set<String> downloads = Set.of("a", "b", "c", "d", "x");
        equal(List.of("x", "b", "c", "d"), Upcoming.plan(List.of("x"), List.of("a", "b", "c", "d"), 0, "a", Set.of("a"), downloads), "explicit queue before fallback");
        equal(List.of("b", "c"), Upcoming.plan(List.of(), List.of("a", "b", "c"), 0, "a", Set.of("a"), downloads), "empty queue follows source list");
        equal(List.of("c", "b"), Upcoming.plan(List.of("c", "c", "missing"), List.of("a", "b", "c"), 0, "a", Set.of("a"), downloads), "deduplicate queued fallback and skip missing downloads");
        equal(List.of("a", "c"), Upcoming.plan(List.of("a"), List.of("a", "b", "c"), 1, "b", Set.of("a", "b"), downloads), "explicit replay is allowed");
        equal(List.of(), Upcoming.plan(List.of(), List.of("a", "b"), 1, "b", Set.of("a", "b"), downloads), "end of source stops");
        equal(List.of("b"), Upcoming.plan(List.of("a"), List.of("a", "b"), 0, "a", Set.of("a"), downloads), "never queue current track twice");

        String rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel><title>Test &amp; Café</title>
                <item><guid>episode-1</guid><title>First</title><pubDate>Wed, 02 Oct 2002 08:00:00 +0000</pubDate>
                  <enclosure url="audio/first.mp3" type="audio/mpeg"/>
                  <description>Short</description><content:encoded><![CDATA[<p>Full show notes</p>]]></content:encoded>
                  <itunes:duration>1:02:03</itunes:duration></item>
                <item><guid>episode-1</guid><title>Duplicate</title><enclosure url="duplicate.mp3"/></item>
                <item><title>Not audio</title><enclosure url="image.jpg" type="image/jpeg"/></item>
                <item><title>Unsafe</title><enclosure url="file:///etc/passwd"/></item>
                <item><title>Undated</title><enclosure url="https://cdn.example.com/b.mp3"/></item>
              </channel>
            </rss>
            """;
        FeedParser.Feed parsed = parse(rss);
        equal("Test & Café", parsed.title(), "RSS title and encoding");
        equal(2, parsed.items().size(), "only distinct audio enclosures");
        equal("https://example.com/audio/first.mp3", parsed.items().get(0).audioUrl(), "relative audio resolves");
        equal(3_723_000L, parsed.items().get(0).duration(), "iTunes duration");
        equal("<p>Full show notes</p>", parsed.items().get(0).description(), "rich content takes precedence");
        equal(Instant.parse("2002-10-02T08:00:00Z").toEpochMilli(), parsed.items().get(0).published(), "RSS publication time");
        equal(0L, parsed.items().get(1).published(), "undated remains undated");
        String atom = """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
              <entry><id>urn:episode:1</id><title>Entry</title><published>2025-01-02T10:30:00+02:00</published>
                <link rel="alternate" href="https://example.com/page"/>
                <link rel="enclosure" href="https://example.com/podcast.m4a" type="audio/mp4"/>
                <summary>Summary</summary></entry></feed>
            """;
        FeedParser.Item entry = parse(atom).items().get(0);
        equal("urn:episode:1", entry.guid(), "Atom ID");
        equal("https://example.com/podcast.m4a", entry.audioUrl(), "Atom enclosure rather than web link");
        equal(Instant.parse("2025-01-02T08:30:00Z").toEpochMilli(), entry.published(), "Atom timezone");
        rejects(() -> parse("<html><title>Not RSS</title></html>"), "reject webpage");
        rejects(() -> parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss><channel><title>&x;</title></channel></rss>"), "reject external entity");
        rejects(() -> FeedParser.parse(new byte[FeedParser.MAX_BYTES + 1], "https://example.com/feed"), "reject oversized feed");
        rejects(() -> FeedParser.httpUrl("javascript:alert(1)"), "reject JavaScript URL");
        rejects(() -> FeedParser.httpUrl("https://name:secret@example.com/feed"), "reject embedded credentials");
        equal("http://example.com/feed", FeedParser.httpUrl(" http://example.com/feed "), "legacy HTTP feed accepted");
        String labeled = FeedListFormat.line("https://example.com/feed?x=1&y=2", "Café & News");
        equal("https://example.com/feed?x=1&y=2 [Café & News]", labeled, "feed export includes title");
        equal(List.of("https://example.com/feed?x=1&y=2"), FeedListFormat.urls(labeled), "labeled export imports the URL only");
        equal(List.of("https://example.com/a", "https://example.com/b"),
            FeedListFormat.urls("\uFEFF  https://example.com/a [First title]\r\n\r\nhttps://example.com/b\nhttps://example.com/a [Another title]\n"),
            "import accepts mixed old and new formats, CRLF, BOM, blank lines and duplicates");
        equal(List.of("https://example.com/feed"), FeedListFormat.urls("https://example.com/feed\tignore anything after the URL"), "import ignores arbitrary trailing text");
        equal("https://example.com/feed [A title on two lines]", FeedListFormat.line("https://example.com/feed", "A title\non two\r\nlines"), "export keeps titles on a single line");
        equal(List.of(), FeedListFormat.urls(" \r\n\t"), "empty feed list");
        equal(List.of("https://example.com/feed"), FeedListFormat.urls("https://example.com/feed everything after the first space is ignored"), "plain space terminates the URL");
        equal(List.of("https://example.com/feed"), FeedListFormat.urls("https://example.com/feed [Title] https://ignored.example.com/feed"), "URLs in the trailing title are not imported");
        equal(List.of("https://example.com/a%20b?x=one%20two"), FeedListFormat.urls("https://example.com/a%20b?x=one%20two [Title]"), "encoded spaces remain part of the URL");
        equal(List.of("https://example.com/feed"), FeedListFormat.urls("\u00A0https://example.com/feed\u202F[Title]"), "non-breaking whitespace is recognized");
        equal(List.of("https://example.com/a", "https://example.com/b", "https://example.com/c"),
            FeedListFormat.urls("https://example.com/a [A]\u2028https://example.com/b [B]\u0085https://example.com/c [C]"), "Unicode line separators are recognized");
        equal("https://example.com/feed [Café 🎧 News]", FeedListFormat.line("https://example.com/feed", "\t Café\u00A0🎧\u202FNews \r\n"), "export normalizes Unicode spacing and preserves title characters");
        equal("", parse(rss).artworkUrl(), "no feed artwork is valid");
        equal("https://example.com/cover.jpg", parse("<rss><channel><title>Feed</title><image><url>/cover.jpg</url></image></channel></rss>").artworkUrl(), "RSS image URL resolves relative to feed");
        equal("https://cdn.example.com/podcast.jpg", parse("<rss xmlns:itunes='http://www.itunes.com/dtds/podcast-1.0.dtd'><channel><image><url>/small.jpg</url></image><itunes:image href='https://cdn.example.com/podcast.jpg'/></channel></rss>").artworkUrl(), "iTunes artwork takes precedence");
        equal("https://example.com/logo.png", parse("<feed xmlns='http://www.w3.org/2005/Atom'><logo>/logo.png</logo><icon>/icon.png</icon></feed>").artworkUrl(), "Atom logo artwork");
        equal("https://example.com/icon.png", parse("<feed xmlns='http://www.w3.org/2005/Atom'><icon>/icon.png</icon></feed>").artworkUrl(), "Atom icon artwork fallback");
        equal("https://example.com/safe.jpg", parse("<rss xmlns:itunes='http://www.itunes.com/dtds/podcast-1.0.dtd'><channel><itunes:image href='file:///private/image.png'/><image><url>/safe.jpg</url></image></channel></rss>").artworkUrl(), "unsafe artwork ignored in favor of safe RSS image");
        equal("", parse("<rss><channel><item><image><url>https://example.com/episode.jpg</url></image></item></channel></rss>").artworkUrl(), "episode artwork does not replace feed artwork");
        System.out.println("PASS: " + checks + " core checks (expiry, protection, heard threshold, resume, queue, RSS, Atom, feed lists, artwork, invalid input)");
    }
    private static FeedParser.Feed parse(String xml) throws Exception { return FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8), "https://example.com/feed.xml"); }
    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation, String message) throws Exception {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }
}

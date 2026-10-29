package com.pod4me.domain;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

/** RSS 2.0 and Atom parser. Only enclosure URLs are treated as playable audio. */
public final class FeedParser {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public record Item(String guid, String title, String audioUrl, String description, long published, long duration) {}
    public record Feed(String title, List<Item> items, String artworkUrl) {
        public Feed(String title, List<Item> items) { this(title, items, ""); }
    }

    public static Feed parse(byte[] bytes, String source) throws Exception {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Feed exceeds 8 MB");
        // Reject DTDs before the XML parser sees them, including UTF-16 input.
        String scan = new String(bytes, StandardCharsets.ISO_8859_1).replace("\u0000", "").toUpperCase(Locale.ROOT);
        if (scan.contains("<!DOCTYPE") || scan.contains("<!ENTITY"))
            throw new IllegalArgumentException("Feeds containing DTDs or entities are unsupported");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities disabled"); });
        Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        boolean atom = name(root).equals("feed");
        Element channel = atom ? root : child(root, "channel");
        if (channel == null || (!atom && !name(root).equals("rss")))
            throw new IllegalArgumentException("This URL is not an RSS or Atom feed");
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element entry : children(channel)) {
            if (!name(entry).equals(atom ? "entry" : "item")) continue;
            String audio = "";
            for (Element e : children(entry)) {
                if (name(e).equals("enclosure") || (name(e).equals("link") && e.getAttribute("rel").equals("enclosure"))) {
                    String type = e.getAttribute("type");
                    if (!type.isEmpty() && !type.startsWith("audio/") && !type.equals("application/octet-stream")) continue;
                    audio = resolve(source, e.getAttribute(atom ? "href" : "url"));
                    if (!audio.isEmpty()) break;
                }
            }
            if (audio.isEmpty()) continue;
            String guid = text(entry, atom ? "id" : "guid");
            if (guid.isEmpty()) guid = audio;
            if (!seen.add(guid)) continue;
            String title = text(entry, "title");
            String description = first(text(entry, "encoded"), text(entry, "content"), text(entry, "description"), text(entry, "summary"));
            String published = first(text(entry, "pubDate"), text(entry, "published"), text(entry, "updated"));
            items.add(new Item(guid, title.isEmpty() ? "Untitled episode" : title, audio, description, date(published), duration(text(entry, "duration"))));
        }
        return new Feed(first(text(channel, "title"), URI.create(source).getHost(), "Podcast"), List.copyOf(items), artwork(channel, source));
    }

    private static String artwork(Element channel, String source) {
        String rssImage = "";
        for (Element e : children(channel)) {
            if (!name(e).equals("image")) continue;
            // Podcast feeds commonly provide an iTunes image in addition to an RSS image.
            String href = resolve(source, e.getAttribute("href"));
            if (!href.isEmpty()) return href;
            if (rssImage.isEmpty()) rssImage = resolve(source, text(e, "url"));
        }
        return first(rssImage, resolve(source, text(channel, "logo")), resolve(source, text(channel, "icon")));
    }

    public static String httpUrl(String value) {
        URI uri;
        try { uri = URI.create(value.trim()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Enter a valid HTTP or HTTPS URL"); }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Enter an HTTP or HTTPS URL without embedded credentials");
        return uri.toASCIIString();
    }

    private static String resolve(String source, String ref) {
        if (ref.isBlank()) return "";
        try { return httpUrl(URI.create(source).resolve(ref.trim()).toString()); } catch (IllegalArgumentException e) { return ""; }
    }
    private static String name(Element e) { return e.getLocalName() == null ? e.getTagName() : e.getLocalName(); }
    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) result.add((Element) n);
        return result;
    }
    private static Element child(Element parent, String name) { for (Element e : children(parent)) if (name(e).equals(name)) return e; return null; }
    private static String text(Element parent, String name) { Element e = child(parent, name); return e == null ? "" : e.getTextContent().trim(); }
    private static String first(String... values) { for (String s : values) if (s != null && !s.isBlank()) return s; return ""; }
    private static long date(String value) {
        if (value.isBlank()) return 0;
        try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        for (DateTimeFormatter f : List.of(DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
                DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.US))) {
            try { return ZonedDateTime.parse(value, f).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        }
        return 0; // Undated episodes stay stable and sort after dated ones.
    }
    private static long duration(String value) {
        try {
            double seconds = 0;
            for (String part : value.split(":")) seconds = seconds * 60 + Double.parseDouble(part);
            return Double.isFinite(seconds) && seconds > 0 ? (long) (seconds * 1000) : 0;
        } catch (NumberFormatException e) { return 0; }
    }
}

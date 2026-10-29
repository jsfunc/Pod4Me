package com.pod4me.domain;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** RSS 2.0 and Atom parser. Only enclosure URLs are treated as playable audio. */
public final class FeedParser {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public record Item(String guid, String title, String audioUrl, String description, long published, long duration) {}
    public record Feed(String title, List<Item> items, String artworkUrl, int skippedItems) {
        public Feed(String title, List<Item> items) { this(title, items, "", 0); }
        public Feed(String title, List<Item> items, String artworkUrl) { this(title, items, artworkUrl, 0); }
    }

    public static Feed parse(byte[] bytes, String source) throws Exception {
        if (bytes.length > MAX_BYTES) throw tooLarge();
        return parse(new ByteArrayInputStream(bytes), source);
    }

    /** The caller owns the input stream. Limits apply to decoded HTTP response bytes. */
    public static Feed parse(InputStream input, String source) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        var reader = factory.newSAXParser().getXMLReader();
        var handler = new FeedHandler(source);
        reader.setContentHandler(handler);
        reader.setEntityResolver(handler);
        reader.setErrorHandler(handler);
        // Reject DTDs before any entity can expand; this also works for UTF-16 feeds.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        reader.parse(new InputSource(new LimitedInput(input)));
        return handler.result();
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("RSS feed exceeds the 64 MB limit. Use a smaller or paginated feed URL if the publisher provides one.");
    }

    private static final class LimitedInput extends InputStream {
        private final InputStream input;
        private long count;
        LimitedInput(InputStream input) { this.input = input; }
        @Override public int read() throws IOException {
            int value = input.read();
            if (value >= 0 && ++count > MAX_BYTES) throw tooLarge();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = input.read(bytes, offset, (int) Math.min(length, MAX_BYTES - count + 1));
            if (read > 0 && (count += read) > MAX_BYTES) throw tooLarge();
            return read;
        }
    }

    /** Retains episode metadata but no DOM tree, raw response buffer, or unrelated XML. */
    private static final class FeedHandler extends DefaultHandler2 {
        private static final String ATOM = "http://www.w3.org/2005/Atom";
        private static final String ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";
        private static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";
        private static final Set<String> ITEM_FIELDS = Set.of("guid", "id", "title", "encoded", "content", "description", "summary", "pubDate", "published", "updated", "duration");
        private final String source;
        private final List<Item> items = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final Map<String, String> feed = new HashMap<>();
        private Map<String, String> entry;
        private boolean atom, sawChannel, inChannel;
        private int depth, channelDepth, entryDepth, imageDepth, captureDepth, skippedItems;
        private String rssNamespace = "";
        private String audio = "", hrefImage = "", rssImage = "", field;
        private StringBuilder capture;

        FeedHandler(String source) { this.source = source; }
        @Override public void startDTD(String name, String publicId, String systemId) {
            throw new IllegalArgumentException("Feeds containing DTDs or entities are unsupported");
        }
        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw new SAXException("External entities disabled");
        }
        @Override public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) throws SAXException {
            throw new SAXException("External entities disabled");
        }
        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
            depth++;
            String name = localName.isEmpty() ? qName : localName;
            if (depth == 1) {
                atom = name.equals("feed") && ATOM.equals(uri);
                if (!atom && !name.equals("rss")) throw new IllegalArgumentException("This URL is not an RSS or Atom feed");
                if (!atom) rssNamespace = uri;
                if (atom) { channelDepth = 1; sawChannel = inChannel = true; }
            } else if (!atom && depth == 2 && name.equals("channel") && canonical(uri) && !sawChannel) {
                channelDepth = 2; sawChannel = inChannel = true;
            }
            if (!inChannel) return;
            if (depth == channelDepth + 1 && name.equals(atom ? "entry" : "item") && canonical(uri)) {
                entry = new HashMap<>(); entryDepth = depth; audio = "";
            } else if (entry != null && depth == entryDepth + 1) {
                if (audio.isEmpty() && canonical(uri) && ((!atom && name.equals("enclosure")) || (atom && name.equals("link") && "enclosure".equals(attributes.getValue("rel"))))) {
                    String type = attributes.getValue("type");
                    if (type == null || type.isEmpty() || type.startsWith("audio/") || type.equals("application/octet-stream"))
                        audio = resolve(source, attributes.getValue(atom ? "href" : "url"));
                }
                if (canonical(uri) && ITEM_FIELDS.contains(name) && !name.equals("encoded") && !name.equals("duration")) beginCapture(name);
                else if (CONTENT.equals(uri) && name.equals("encoded")) beginCapture("encoded");
                else if (ITUNES.equals(uri) && Set.of("title", "summary", "duration").contains(name)) beginCapture("itunes:" + name);
            } else if (entry == null && depth == channelDepth + 1) {
                if (canonical(uri) && (name.equals("title") || (atom && (name.equals("logo") || name.equals("icon"))))) beginCapture(name);
                if (name.equals("image") && ((!atom && canonical(uri)) || ITUNES.equals(uri))) {
                    if (!atom && canonical(uri)) imageDepth = depth;
                    if (ITUNES.equals(uri) && hrefImage.isEmpty()) hrefImage = resolve(source, attributes.getValue("href"));
                }
            } else if (entry == null && imageDepth > 0 && depth == imageDepth + 1 && name.equals("url") && canonical(uri)) beginCapture("imageUrl");
        }
        // RSS publishers sometimes put the whole document in a default namespace.
        // Match that document namespace, while keeping extension fields separate.
        private boolean canonical(String uri) { return atom ? ATOM.equals(uri) : rssNamespace.equals(uri); }
        private void beginCapture(String name) { field = name; captureDepth = depth; capture = new StringBuilder(); }
        @Override public void characters(char[] chars, int start, int length) {
            if (capture != null) capture.append(chars, start, length);
        }
        @Override public void endElement(String uri, String localName, String qName) {
            if (capture != null && depth == captureDepth) {
                String value = capture.toString().trim();
                if (entry != null) entry.putIfAbsent(field, value);
                else if (field.equals("imageUrl")) { if (rssImage.isEmpty()) rssImage = resolve(source, value); }
                else feed.putIfAbsent(field, value);
                capture = null;
            }
            if (entry != null && depth == entryDepth) { finishEntry(); entry = null; }
            if (depth == imageDepth) imageDepth = 0;
            if (depth == channelDepth) inChannel = false;
            depth--;
        }
        private void finishEntry() {
            if (audio.isEmpty()) { skippedItems++; return; }
            String guid = entry.getOrDefault(atom ? "id" : "guid", "");
            if (guid.isEmpty()) guid = audio;
            if (!seen.add(guid)) return;
            String title = first(entry.get("title"), entry.get("itunes:title"), "Untitled episode");
            String description = first(entry.get("encoded"), entry.get("content"), entry.get("description"), entry.get("summary"), entry.get("itunes:summary"));
            String published = first(entry.get("pubDate"), entry.get("published"), entry.get("updated"));
            items.add(new Item(guid, title, audio, description, date(published), duration(entry.getOrDefault("itunes:duration", ""))));
        }
        Feed result() {
            if (!sawChannel) throw new IllegalArgumentException("This URL is not an RSS or Atom feed");
            String artwork = first(hrefImage, rssImage, resolve(source, feed.get("logo")), resolve(source, feed.get("icon")));
            return new Feed(first(feed.get("title"), URI.create(source).getHost(), "Podcast"), List.copyOf(items), artwork, skippedItems);
        }
    }

    public static String httpUrl(String value) {
        URI uri;
        try { uri = URI.create(value.trim()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Enter a valid HTTP or HTTPS URL"); }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Enter an HTTP or HTTPS URL without embedded credentials");
        return uri.toASCIIString();
    }

    private static String resolve(String source, String ref) {
        if (ref == null || ref.isBlank()) return "";
        try { return httpUrl(URI.create(source).resolve(escapeReference(ref.trim())).toString()); } catch (IllegalArgumentException e) { return ""; }
    }
    private static String escapeReference(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9') ||
                    "-._~:/?#[]@!$&'()*+,;=".indexOf(cp) >= 0) result.appendCodePoint(cp);
            else if (cp == '%' && i + 2 < value.length() && Character.digit(value.charAt(i + 1), 16) >= 0 && Character.digit(value.charAt(i + 2), 16) >= 0)
                result.append('%');
            else for (byte b : new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                result.append('%').append("0123456789ABCDEF".charAt((b & 255) >> 4)).append("0123456789ABCDEF".charAt(b & 15));
            }
            i += Character.charCount(cp);
        }
        return result.toString();
    }
    private static String first(String... values) { for (String s : values) if (s != null && !s.isBlank()) return s; return ""; }
    private static long date(String value) {
        if (value.isBlank()) return 0;
        value = value.replaceFirst("\\s*\\([^()]*\\)\\s*$", "").trim();
        Map<String, String> zones = Map.ofEntries(Map.entry("UT", "+0000"), Map.entry("UTC", "+0000"),
            Map.entry("GMT", "+0000"), Map.entry("EST", "-0500"), Map.entry("EDT", "-0400"),
            Map.entry("CST", "-0600"), Map.entry("CDT", "-0500"), Map.entry("MST", "-0700"),
            Map.entry("MDT", "-0600"), Map.entry("PST", "-0800"), Map.entry("PDT", "-0700"));
        int lastSpace = value.lastIndexOf(' ');
        String offset = zones.get(value.substring(lastSpace + 1).toUpperCase(Locale.ROOT));
        if (offset != null) value = value.substring(0, lastSpace + 1) + offset;
        try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        for (DateTimeFormatter f : List.of(DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
                DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.US))) {
            try { return ZonedDateTime.parse(value, f).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        }
        try { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignored) {}
        try { return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        return 0; // Undated episodes stay stable and sort after dated ones.
    }
    private static long duration(String value) {
        try {
            double seconds = 0;
            String[] parts = value.split(":", -1);
            if (parts.length > 3) return 0;
            for (int i = 0; i < parts.length; i++) {
                double part = Double.parseDouble(parts[i]);
                if (part < 0 || (i > 0 && part >= 60)) return 0;
                seconds = seconds * 60 + part;
            }
            return Double.isFinite(seconds) && seconds > 0 && seconds <= 7 * 86400 ? (long) (seconds * 1000) : 0;
        } catch (NumberFormatException e) { return 0; }
    }
}

package com.pod4me.domain;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** Bounded OPML/text interchange. Parsing never fetches or subscribes to anything. */
public final class SubscriptionFormat {
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_FEEDS = 1000;
    public record Entry(String url, String title) {}
    private SubscriptionFormat() {}

    public static List<Entry> parse(byte[] bytes) throws Exception {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Feed list must be smaller than 1 MB");
        String text = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
        boolean utf16 = bytes.length >= 2 && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254 ||
                (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255 || bytes[0] == 0 || bytes[1] == 0);
        if (!utf16 && !text.stripLeading().startsWith("<")) {
            var result = new ArrayList<Entry>();
            for (String line : text.split("[\\r\\n\\u000B\\f\\u0085\\u2028\\u2029]")) {
                var urls = FeedListFormat.urls(line);
                if (urls.isEmpty()) continue;
                String url = urls.get(0);
                String rest = line.substring(line.indexOf(url) + url.length()).trim();
                String title = rest.startsWith("[") && rest.endsWith("]") ? rest.substring(1, rest.length() - 1) : "";
                result.add(entry(url, title));
                checkCount(result.size());
            }
            return List.copyOf(result);
        }
        var factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        var reader = factory.newSAXParser().getXMLReader();
        var handler = new DefaultHandler2() {
            final List<Entry> entries = new ArrayList<>();
            int depth;
            int bodyDepth;
            @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
                throw new SAXException("OPML with DTDs or entities is unsupported");
            }
            @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                throw new SAXException("External entities are unsupported");
            }
            @Override public InputSource resolveEntity(String name, String publicId, String base, String systemId) throws SAXException {
                throw new SAXException("External entities are unsupported");
            }
            @Override public void startElement(String uri, String local, String qName, Attributes attributes) throws SAXException {
                if (++depth > 64) throw new SAXException("OPML is nested too deeply");
                if (depth == 1 && !local.equals("opml")) throw new SAXException("Choose an OPML or text subscription list");
                if (depth == 2 && local.equals("body")) bodyDepth = depth;
                if (bodyDepth > 0 && local.equals("outline")) {
                    String url = attributes.getValue("xmlUrl");
                    if (url != null) {
                        String title = attributes.getValue("title");
                        if (title == null || title.isBlank()) title = attributes.getValue("text");
                        entries.add(entry(url, title));
                        checkCount(entries.size());
                    }
                }
            }
            @Override public void endElement(String uri, String local, String qName) {
                if (depth == bodyDepth) bodyDepth = 0;
                depth--;
            }
        };
        reader.setContentHandler(handler);
        reader.setEntityResolver(handler);
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        try { reader.parse(new InputSource(new ByteArrayInputStream(bytes))); }
        catch (SAXException e) { throw new IllegalArgumentException("Invalid OPML: " + e.getMessage(), e); }
        return List.copyOf(handler.entries);
    }

    public static String opml(List<Entry> feeds) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\"><head><title>Pod4Me subscriptions</title></head><body>\n");
        for (Entry feed : feeds) xml.append("  <outline type=\"rss\" text=\"").append(escape(feed.title()))
            .append("\" title=\"").append(escape(feed.title())).append("\" xmlUrl=\"").append(escape(feed.url())).append("\"/>\n");
        return xml.append("</body></opml>\n").toString();
    }
    private static Entry entry(String url, String title) {
        if (url.length() > 65536) throw new IllegalArgumentException("A subscription URL is too long");
        return new Entry(url.trim(), title == null ? "" : title.trim().substring(0, Math.min(title.trim().length(), 4096)));
    }
    private static void checkCount(int size) {
        if (size > MAX_FEEDS) throw new IllegalArgumentException("Import at most 1,000 feeds at a time");
    }
    private static String escape(String text) {
        StringBuilder clean = new StringBuilder();
        text.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF).forEach(clean::appendCodePoint);
        return clean.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;").replace("\n", "&#10;").replace("\r", "&#13;");
    }
}

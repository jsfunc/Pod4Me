package com.pod4me.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Human-readable feed lists; only the first whitespace-delimited field is imported.
 * Uses character scanning because Android's ICU regex engine rejects Java's (?U) flag.
 */
public final class FeedListFormat {
    private FeedListFormat() {}

    public static List<String> urls(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var urls = new LinkedHashSet<String>();
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && !lineBreak(text.charAt(i))) continue;
            int start = lineStart;
            while (start < i && space(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
            int end = start;
            while (end < i && !space(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end));
            if (end > start) urls.add(text.substring(start, end));
            lineStart = i + 1;
        }
        return new ArrayList<>(urls);
    }

    public static String line(String url, String title) {
        var normalizedTitle = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < title.length();) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            if (space(cp)) {
                pendingSpace = normalizedTitle.length() > 0;
            } else {
                if (pendingSpace) normalizedTitle.append(' ');
                normalizedTitle.appendCodePoint(cp);
                pendingSpace = false;
            }
        }
        return url + " [" + normalizedTitle + "]";
    }

    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
    }

    private static boolean lineBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u000B' || c == '\f' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }
}

package com.pod4me.domain;

import java.util.*;

/** Explicit requests take priority; the source list supplies the remaining episodes. */
public final class Upcoming {
    private Upcoming() {}
    public static List<String> plan(List<String> queue, List<String> source, int cursor,
                                    String current, Set<String> history, Set<String> downloaded) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        // Explicitly queueing a previously played episode is allowed.
        for (String id : queue) if (!id.equals(current) && downloaded.contains(id)) result.add(id);
        for (int i = Math.max(0, cursor + 1); i < source.size(); i++) {
            String id = source.get(i);
            if (!id.equals(current) && !history.contains(id) && downloaded.contains(id)) result.add(id);
        }
        return new ArrayList<>(result);
    }
}

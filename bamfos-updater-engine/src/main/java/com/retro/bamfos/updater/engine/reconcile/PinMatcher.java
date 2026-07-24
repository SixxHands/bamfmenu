package com.retro.bamfos.updater.engine.reconcile;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Matches pin= globs case-insensitively across both manifest and ledger views so pinned files are never overwritten or deleted.
public final class PinMatcher {

    private final List<Pattern> patterns;

    public PinMatcher(List<String> globs) {
        patterns = globs.stream().map(PinMatcher::compile).toList();
    }

    public boolean pinned(String path) {
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(x -> x.matcher(p).matches());
    }

    private static Pattern compile(String glob) {
        StringBuilder re = new StringBuilder();
        String g = glob.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        re.append(".*");
                        i++;
                    } else {
                        re.append("[^/]*");
                    }
                }
                case '?' -> re.append("[^/]");
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }
}

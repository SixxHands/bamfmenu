package com.retro.bamfmenu.early.core.repair.api;

import com.retro.bamfmenu.early.core.updater.OpsBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rollback picker rows plus the listReleases result code.
 * Keep the code with the rows: offline still returns the installed version as a single row.
 */
public record ReleaseList(int code, List<Release> rows) {

    /** A release shown in the rollback picker. `installed` is true for the currently installed release. */
    public record Release(String tag, String name, String publishedAt, boolean prerelease, boolean installed) {

        /** Release date without the timestamp, so it fits in the picker. */
        public String day() {
            int t = publishedAt.indexOf('T');
            return t > 0 ? publishedAt.substring(0, t) : publishedAt;
        }
    }

    /** Codes as the engine sends them: 0 live · 2 offline · 4 token dead · 5 rate limited. */
    public static final int LIVE = 0;
    public static final int OFFLINE = 2;
    public static final int TOKEN_DEAD = 4;
    public static final int RATE_LIMITED = 5;
    /** No status code returned, meaning the engine predates listReleases rather than a network error. */
    public static final int NO_ANSWER = OpsBridge.NO_ANSWER;

    /**
     * Parses the listReleases map into rows, dropping entries without a tag.
     * Missing "code" maps to NO_ANSWER (not offline), since older engines flatten unknown-op errors to an empty map.
     */
    public static ReleaseList of(Map<String, Object> answer, List<Map<String, Object>> rawRows) {
        List<Release> out = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            String tag = row.get("tag") instanceof String s ? s : "";
            if (!tag.isEmpty()) {
                out.add(new Release(tag,
                        row.get("name") instanceof String n ? n : "",
                        row.get("published_at") instanceof String at ? at : "",
                        Boolean.TRUE.equals(row.get("prerelease")),
                        Boolean.TRUE.equals(row.get("installed"))));
            }
        }
        int code = answer.get("code") instanceof Number n ? n.intValue() : NO_ANSWER;
        return new ReleaseList(code, List.copyOf(out));
    }

    /** True if the release list came from the network instead of falling back to local info. */
    public boolean live() {
        return code == LIVE;
    }

    /**
     * True only when a LIVE list has exactly one release and it matches our running version.
     * Offline caps at one row and an empty repo is a separate state, so neither counts here.
     */
    public boolean nothingOlder() {
        return live() && rows.size() == 1 && rows.get(0).installed();
    }
}

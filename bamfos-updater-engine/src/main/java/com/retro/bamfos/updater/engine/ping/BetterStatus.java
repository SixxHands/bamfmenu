package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.json.MiniJson;

import java.util.Map;
import java.util.Optional;

// Parses BCC's betterStatus object from server ping JSON. Missing key or literal CHANGE_ME means no signal.
public record BetterStatus(String name, String version) {

    public static Optional<BetterStatus> parse(String statusJson) {
        try {
            Object root = MiniJson.parse(statusJson);
            if (root instanceof Map<?, ?> m && m.get("betterStatus") instanceof Map<?, ?> bs
                    && bs.get("name") instanceof String name
                    && bs.get("version") instanceof String version
                    && !version.isEmpty() && !"CHANGE_ME".equals(version) && !"CHANGE_ME".equals(name)) {
                return Optional.of(new BetterStatus(name, version));
            }
        } catch (RuntimeException notJson) {
            // a garbage ping is a no-signal, never a crash
        }
        return Optional.empty();
    }
}

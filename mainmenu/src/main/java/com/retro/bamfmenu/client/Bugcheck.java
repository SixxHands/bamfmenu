package com.retro.bamfmenu.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the mismatch BSOD text from NeoForge's kick translation keys. Keeps Minecraft imports out so
 * classification and text are unit-testable and the offline renderer matches in-game output.
 * Recurrences only escalate to BAMF_SYNC_CONVERGENCE_FAILED, never cannot-handle (that's only for
 * terminal protocol keys).
 */
public record Bugcheck(String diagnosis, String listHeader, List<String> rows, String consequence,
                       String action, String stopCode) {

    // One failed channel: modid plus every translation key in its reason tree (outermost first).
    public record Failure(String modid, List<String> keys) {
    }

    /** Block role on the bugcheck screen, used to pick what to drop on small windows (see {@link #fit}). */
    public enum Role { OPENING, DIAGNOSIS, HEADER, ROW, CONSEQUENCE, ACTION, TECH, STOP, GAP }

    /** One rendered paragraph with its role, text, and left indent in pixels. */
    public record Block(Role role, String text, int indent) {
    }

    /** What the pack can actually do about one failed channel. */
    private enum Fault { EXTRA, MISSING, SKEW, UNKNOWN, PLATFORM, TERMINAL }

    public static final String OPENING =
            "A problem has been detected and BAMFOS has been shut down to prevent damage to your installation.";
    public static final String TECHNICAL = "Technical information:";
    public static final String EXIT_ESC = "*  Press ESC to return to the error details.";
    public static final String EXIT_ENTER = "*  Press ENTER to close the game.";
    private static final String RESTART = "RESTART YOUR GAME SO THESE CHANGES CAN BE APPLIED.";
    /** Log path shown on screen, replacing the open-file button on NeoForge's ModMismatchDisconnectedScreen. */
    private static final String CONTACT = "Contact the pack owner and send logs/latest.log from this play session.";

    /** Max listed rows before collapsing the rest into a "+N more" line, since the screen doesn't scroll. */
    private static final int MAX_ROWS = 8;
    private static final int ROW_INDENT = 16;
    /** Matches the "+N more" line from cap() so collapseRows can fold its count in instead of dropping it. */
    private static final Pattern REMAINDER = Pattern.compile("\\+(\\d+) more");

    /** Platform namespaces to skip so we don't list things like "minecraft" as a culprit mod. */
    private static final Set<String> PLATFORM = Set.of("minecraft", "neoforge", "forge");

    /** Client sent a channel the server didn't register, meaning the client has a mod the server doesn't. */
    private static final Set<String> EXTRA_KEYS = Set.of(
            "neoforge.network.negotiation.failure.missing.client.server",
            "neoforge.network.data_maps.missing_our");

    /** Server requires content this client doesn't have. `server-with-unknown-keys` means the server
     *  registered IDs the client can't resolve. */
    private static final Set<String> MISSING_KEYS = Set.of(
            "neoforge.network.negotiation.failure.missing.server.client",
            "neoforge.network.registries.sync.server-with-unknown-keys",
            "neoforge.network.data_maps.missing_their");

    // Same mod on both sides at different payload versions.
    private static final Set<String> SKEW_KEYS = Set.of(
            "neoforge.network.negotiation.failure.version.mismatch",
            "neoforge.network.negotiation.failure.version.client.missing",
            "neoforge.network.negotiation.failure.version.server.missing",
            "neoforge.network.extensible_enums.enum_entry_mismatch",
            "neoforge.network.feature_flags.entry_mismatch");

    // Game version mismatch keys (not fixable by pack sync; multiplayer.disconnect.incompatible stays out since NeoForge uses it for mod negotiation).
    private static final Set<String> PLATFORM_KEYS = Set.of(
            "multiplayer.disconnect.outdated_client",
            "multiplayer.disconnect.outdated_server");

    /** Protocol-level errors (wrong payload direction, handler exception, non-NeoForge server) that syncing
     *  mods can't fix. */
    private static final Set<String> TERMINAL_KEYS = Set.of(
            "neoforge.network.negotiation.failure.flow.client.missing",
            "neoforge.network.negotiation.failure.flow.server.missing",
            "neoforge.network.negotiation.failure.flow.client.mismatch",
            "neoforge.network.negotiation.failure.flow.server.mismatch",
            "neoforge.network.invalid_flow",
            "neoforge.network.packet_splitter.unknown",
            "neoforge.network.advanced_add_entity.failed",
            "neoforge.network.advanced_open_screen.failed",
            "neoforge.network.aux_light_data.failed",
            "neoforge.network.data_maps.failed",
            "neoforge.network.negotiation.failure.vanilla.server.not_supported",
            "neoforge.network.extensible_enums.no_vanilla_server",
            "neoforge.network.feature_flags.no_vanilla_server");

    /**
     * Classify a kick into a bugcheck screen. `failures` has per-channel errors (empty if none were sent),
     * `rawReason` is the flattened text saved to the breadcrumb, and `recurredUnchanged` is true if the
     * last sync for this exact kick didn't change anything.
     */
    public static Bugcheck of(List<Failure> failures, String rawReason, boolean recurredUnchanged) {
        Set<Fault> definite = new LinkedHashSet<>();
        for (Failure f : failures) {
            definite.addAll(faults(f.keys()));
        }
        // terminal channels outrank syncable faults and recurrences (if one channel can't be synced, telling you to restart is a lie)
        if (definite.contains(Fault.TERMINAL)) {
            return new Bugcheck("BAMFOS ran into a problem it cannot handle.", "", rawRows(rawReason),
                    "A sync will not clear this on its own. " + CONTACT,
                    "", "BAMF_PROTOCOL_FAULT_NOT_HANDLED");
        }
        // game version mismatch won't fix itself on restart either, so skip the restart prompt
        if (definite.contains(Fault.PLATFORM)) {
            return new Bugcheck("Minecraft version mismatch detected.", "", rawRows(rawReason),
                    "The server runs a different version of the game, and a package sync cannot change that. "
                            + CONTACT, "", "BAMF_GAME_VERSION_MISMATCH");
        }
        List<String> names = names(failures);
        Bugcheck base = switch (definite.size() == 1 ? definite.iterator().next() : Fault.UNKNOWN) {
            case EXTRA -> new Bugcheck("Client package mismatch detected.", "Unexpected packages:", names,
                    "These packages are not present on the server.", RESTART,
                    "BAMF_CLIENT_PACKAGE_UNEXPECTED");
            case MISSING -> new Bugcheck("Client package mismatch detected.", "Missing packages:", names,
                    "The server is running these and your client is not.", RESTART,
                    "BAMF_CLIENT_PACKAGE_MISSING");
            // names only, NOT the numbers (version.mismatch gives the payload protocol version,
            // which would send you hunting for a JourneyMap 2.0 that doesn't exist)
            case SKEW -> new Bugcheck("Client package revision mismatch detected.",
                    "Packages at a different revision:", names,
                    "Your copy of these is not the revision the server is running.", RESTART,
                    "BAMF_PACKAGE_REVISION_MISMATCH");
            default -> new Bugcheck("Client and server do not agree on the pack contents.",
                    names.isEmpty() ? "" : "Packages involved:", names,
                    "The next boot syncs your client to the server.", RESTART, "BAMF_SYNC_REQUIRED");
        };
        if (names.isEmpty()) {
            // no channel gave us a mod ID, so show the raw kick text without a mod list header
            base = new Bugcheck(base.diagnosis, "", rawRows(rawReason), base.consequence, base.action,
                    base.stopCode);
        }
        return recurredUnchanged ? converged(base) : base;
    }

    // Anti-loop screen: we know what the mismatch is, the last sync just didn't fix it.
    private static Bugcheck converged(Bugcheck base) {
        return new Bugcheck("This is the same mismatch as the last attempt, and the last sync changed nothing.",
                base.listHeader, base.rows,
                "Restarting again will not clear it on its own. " + CONTACT,
                "", "BAMF_SYNC_CONVERGENCE_FAILED");
    }

    // Drops the restart prompt when writing the breadcrumb failed, otherwise the next boot does nothing
    // and you loop on this screen till the end of time (bad ending).
    public Bugcheck unrecorded() {
        if (action.isEmpty()) {
            return this;
        }
        return new Bugcheck(diagnosis, listHeader, rows,
                "BAMFOS could not record this kick, so a restart cannot act on it. " + CONTACT,
                "", stopCode);
    }

    /**
     * Sample kicks shared by BAMFMENU_BSOD_DEMO and the offline renderer so previews match in-game output.
     * Each sample uses actual NeoForge keys and reason text so previews stay in sync with the classifier.
     */
    public static Bugcheck sample(String kind) {
        String wrapper = "neoforge.network.negotiation.failure.mod";
        List<Failure> extra = List.of(
                new Failure("supplementaries", List.of(wrapper,
                        "neoforge.network.negotiation.failure.missing.client.server")),
                new Failure("chipped", List.of(wrapper,
                        "neoforge.network.negotiation.failure.missing.client.server")));
        String extraRaw = "Mod channels failed to negotiate with the server."
                + "\nsupplementaries:main: This channel is missing on the server side, but required on the client!"
                + "\nchipped:main: This channel is missing on the server side, but required on the client!";
        return switch (kind) {
            case "missing" -> of(List.of(new Failure("journeymap", List.of(wrapper,
                    "neoforge.network.negotiation.failure.missing.server.client"))),
                    "Mod channels failed to negotiate with the server."
                            + "\njourneymap:main: This channel is missing on the client side,"
                            + " but required on the server!", false);
            case "skew" -> of(List.of(new Failure("journeymap", List.of(wrapper,
                    "neoforge.network.negotiation.failure.version.mismatch"))),
                    "Mod channels failed to negotiate with the server."
                            + "\njourneymap:main: The client wants the payload to be version: 2,"
                            + " but the server wants it to be version: 1!", false);
            case "unclassified" -> of(List.of(new Failure("", List.of("multiplayer.disconnect.incompatible"))),
                    "Incompatible client! Please use NeoForge 21.1.235", false);
            // arg is the server's game version, shown to players on an outdated profile
            case "platform" -> of(List.of(new Failure("", List.of("multiplayer.disconnect.outdated_client"))),
                    "Incompatible client! Please use 1.21.1", false);
            case "terminal" -> of(List.of(new Failure("bamfnether", List.of(wrapper,
                    "neoforge.network.negotiation.failure.flow.client.missing"))),
                    "Mod channels failed to negotiate with the server."
                            + "\nbamfnether:main: The client wants the payload to flow: PLAY_TO_SERVER,"
                            + " but the server does not support it!", false);
            case "recurrence" -> of(extra, extraRaw, true);
            default -> of(extra, extraRaw, false);
        };
    }

    // Collect every fault across the key chain so mixed opposite faults (like bidirectional data map mismatches) fall back to the generic sync message.
    private static Set<Fault> faults(List<String> keys) {
        Set<Fault> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (TERMINAL_KEYS.contains(key)) {
                out.add(Fault.TERMINAL);
            } else if (PLATFORM_KEYS.contains(key)) {
                out.add(Fault.PLATFORM);
            } else if (EXTRA_KEYS.contains(key)) {
                out.add(Fault.EXTRA);
            } else if (MISSING_KEYS.contains(key)) {
                out.add(Fault.MISSING);
            } else if (SKEW_KEYS.contains(key)) {
                out.add(Fault.SKEW);
            }
        }
        return out;
    }

    /** Culprit package names off the channel namespaces, deduped in kick order. */
    private static List<String> names(List<Failure> failures) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Failure f : failures) {
            String id = f.modid() == null ? "" : f.modid().trim();
            if (!id.isEmpty() && !PLATFORM.contains(id)) {
                out.add(id);
            }
        }
        return cap(new ArrayList<>(out));
    }

    /** Split the kick text into one row per line. */
    private static List<String> rawRows(String rawReason) {
        List<String> out = new ArrayList<>();
        for (String line : (rawReason == null ? "" : rawReason).split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return cap(out);
    }

    private static List<String> cap(List<String> rows) {
        if (rows.size() <= MAX_ROWS) {
            return List.copyOf(rows);
        }
        List<String> out = new ArrayList<>(rows.subList(0, MAX_ROWS));
        out.add("+" + (rows.size() - MAX_ROWS) + " more");
        return List.copyOf(out);
    }

    /** Screen body blocks in draw order. Empty headers or actions are omitted (no list header without
     *  entries, and no restart prompt when restarting won't help). */
    public List<Block> body() {
        List<Block> out = new ArrayList<>();
        out.add(new Block(Role.OPENING, OPENING, 0));
        out.add(gap());
        out.add(new Block(Role.DIAGNOSIS, diagnosis, 0));
        out.add(gap());
        if (!listHeader.isEmpty() && !rows.isEmpty()) {
            out.add(new Block(Role.HEADER, listHeader, 0));
        }
        for (String row : rows) {
            out.add(new Block(Role.ROW, row, ROW_INDENT));
        }
        if (!rows.isEmpty()) {
            out.add(gap());
        }
        out.add(new Block(Role.CONSEQUENCE, consequence, 0));
        if (!action.isEmpty()) {
            out.add(new Block(Role.ACTION, action, 0));
        }
        out.add(gap());
        out.add(new Block(Role.TECH, TECHNICAL, 0));
        out.add(gap());
        out.add(new Block(Role.STOP, "*** STOP: " + stopCode, 0));
        return out;
    }

    private static Block gap() {
        return new Block(Role.GAP, "", 0);
    }

    /** Join all body lines into one string so tests can check that specific wording isn't shown. */
    public String text() {
        StringBuilder b = new StringBuilder();
        for (Block block : body()) {
            b.append(block.text()).append('\n');
        }
        return b.toString();
    }

    /* Trims the body to fit smaller windows (shedding gaps, header label, trailing rows, then framing).
     * Diagnosis, action, and STOP code no go, bugcheck needs to produce something readable or whatever. */
    public static List<Block> fit(List<Block> blocks, ToIntFunction<Block> lines, int budget) {
        List<Block> kept = new ArrayList<>(blocks);
        for (Role shed : List.of(Role.GAP, Role.TECH)) {
            if (height(kept, lines) <= budget) {
                return kept;
            }
            kept.removeIf(b -> b.role() == shed);
        }
        collapseRows(kept, lines, budget);
        for (Role shed : List.of(Role.OPENING, Role.CONSEQUENCE, Role.HEADER, Role.ROW)) {
            if (height(kept, lines) <= budget) {
                return kept;
            }
            // when there's no restart prompt, the consequence block is the only instruction (contact the server
            // host, send the log), so keep it ahead of the mod list rows
            if (shed == Role.CONSEQUENCE && kept.stream().noneMatch(b -> b.role() == Role.ACTION)) {
                continue;
            }
            kept.removeIf(b -> b.role() == shed);
        }
        return kept;
    }

    // Trim trailing rows until the body fits and append "+N more". We only reserve the remainder line
    // once a row is dropped, and fold in any existing cap() count so it isn't dropped like a normal row.
    private static void collapseRows(List<Block> kept, ToIntFunction<Block> lines, int budget) {
        int dropped = takeRemainder(kept);
        while (rowCount(kept) > 1 && height(kept, lines) + (dropped > 0 ? 1 : 0) > budget) {
            dropped += removeLastRow(kept);
        }
        if (dropped > 0) {
            kept.add(lastRow(kept) + 1, new Block(Role.ROW, "+" + dropped + " more", ROW_INDENT));
        }
    }

    /** Pop and return the count from a "+N more" row added by cap() (NeoForge kicks never use this format). */
    private static int takeRemainder(List<Block> kept) {
        int i = lastRow(kept);
        if (i < 0) {
            return 0;
        }
        Matcher m = REMAINDER.matcher(kept.get(i).text());
        if (!m.matches()) {
            return 0;
        }
        kept.remove(i);
        return Integer.parseInt(m.group(1));
    }

    private static int removeLastRow(List<Block> kept) {
        int i = lastRow(kept);
        if (i < 0) {
            return 0;
        }
        kept.remove(i);
        return 1;
    }

    private static int lastRow(List<Block> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (blocks.get(i).role() == Role.ROW) {
                return i;
            }
        }
        return -1;
    }

    private static int rowCount(List<Block> blocks) {
        int n = 0;
        for (Block b : blocks) {
            if (b.role() == Role.ROW) {
                n++;
            }
        }
        return n;
    }

    private static int height(List<Block> blocks, ToIntFunction<Block> lines) {
        int n = 0;
        for (Block b : blocks) {
            n += lines.applyAsInt(b);
        }
        return n;
    }
}

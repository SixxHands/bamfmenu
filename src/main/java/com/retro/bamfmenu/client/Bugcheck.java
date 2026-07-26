package com.retro.bamfmenu.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every word the join-guard blue screen prints, decided off the REAL NeoForge translation keys the kick
 * carried. No Minecraft imports on purpose, so the classification and the copy are both
 * unit-testable and the offline renderer draws the exact strings the game draws.
 * IMPORTANT: a recurrence may only escalate to BAMF_SYNC_CONVERGENCE_FAILED, NEVER to the cannot-handle
 * screen. That one takes a terminal-class key, because an extra package, a missing package and a revision
 * skew are all things the next boot converges, however many times they come back.
 */
public record Bugcheck(String diagnosis, String listHeader, List<String> rows, String consequence,
                       String action, String stopCode) {

    /** One failed channel: the modid off the channel id, and every translation key in its reason
     *  component, outermost first. NeoForge re-wraps a channel's reason in
     *  `neoforge.network.negotiation.failure.mod` whenever it can name the owning mod
     *  (ModMismatchDisconnectedScreen's ctor, NetworkComponentNegotiator:84), so the key that
     *  classifies sits DOWN the tree, not at the top. */
    public record Failure(String modid, List<String> keys) {
    }

    /** Where a block sits in the bugcheck, so a tight window sheds the right lines (see {@link #fit}). */
    public enum Role { OPENING, DIAGNOSIS, HEADER, ROW, CONSEQUENCE, ACTION, TECH, STOP, GAP }

    /** One rendered paragraph: its role, its text, and the pixels it hangs in from the column edge. */
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
    /** The one place the log is named. The NeoForge screen ESC hands back has an open-file button for it
     *  (ModMismatchDisconnectedScreen:86), so this is the affordance the override borrowed, given back. */
    private static final String CONTACT = "Contact the pack owner and send logs/latest.log from this play session.";

    /** A screen with no scroll bar, so past this the tail becomes one honest remainder row. */
    private static final int MAX_ROWS = 8;
    private static final int ROW_INDENT = 16;
    /** The remainder row cap() writes, so a tight window can pick its COUNT back up instead of shedding the
     *  one row that carried the truth (see {@link #collapseRows}). */
    private static final Pattern REMAINDER = Pattern.compile("\\+(\\d+) more");

    /** These name the platform, never a pack package, and a `minecraft` row would read as the culprit. */
    private static final Set<String> PLATFORM = Set.of("minecraft", "neoforge", "forge");

    /** The client offers a channel the server never registered, so the client is carrying a package the
     *  server does not run. */
    private static final Set<String> EXTRA_KEYS = Set.of(
            "neoforge.network.negotiation.failure.missing.client.server",
            "neoforge.network.data_maps.missing_our");

    /** The server offers content this client has no mod for. `server-with-unknown-keys` is the strongest
     *  one NeoForge emits: the server registered ids the client cannot resolve. */
    private static final Set<String> MISSING_KEYS = Set.of(
            "neoforge.network.negotiation.failure.missing.server.client",
            "neoforge.network.registries.sync.server-with-unknown-keys",
            "neoforge.network.data_maps.missing_their");

    /** Both sides HAVE the channel and disagree on its payload version, which is the same mod at two
     *  revisions. The two `version.*.missing` keys belong here and NOT in the missing-package set: the
     *  channel matched, so both sides run the mod, and it is the VERSION the other side will not take. */
    private static final Set<String> SKEW_KEYS = Set.of(
            "neoforge.network.negotiation.failure.version.mismatch",
            "neoforge.network.negotiation.failure.version.client.missing",
            "neoforge.network.negotiation.failure.version.server.missing",
            "neoforge.network.extensible_enums.enum_entry_mismatch",
            "neoforge.network.feature_flags.entry_mismatch");

    /** The platform's own handshake refusing a protocol number, which is a GAME version and not a channel at
     *  all. Vanilla renders both as "Incompatible client! Please use <version>" off
     *  SharedConstants.getCurrentVersion().getName() (ServerHandshakePacketListenerImpl:60). They used to sit
     *  in SKEW_KEYS, where the screen diagnosed a package revision and ordered a restart no sync can honor.
     *  `multiplayer.disconnect.incompatible` stays OUT of here on purpose: NeoForge sends that same key for a
     *  failed negotiation with "NeoForge <build>" as its arg (NetworkRegistry:369), which a sync really does
     *  fix, so it keeps degrading to the sync case. */
    private static final Set<String> PLATFORM_KEYS = Set.of(
            "multiplayer.disconnect.outdated_client",
            "multiplayer.disconnect.outdated_server");

    /** A wire-level fault: a payload flowing the wrong way, a handler that threw, a server with no
     *  NeoForge at all. No package set and no version explains these, so no sync clears them. */
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
     * The variant for one kick. `failures` is per-channel evidence (empty for a kick that carried no
     * channel data), `rawReason` is the flattened text the breadcrumb keeps, `recurredUnchanged` is the
     * engine's answer to "did the last cycle for this exact fingerprint change anything".
     */
    public static Bugcheck of(List<Failure> failures, String rawReason, boolean recurredUnchanged) {
        Set<Fault> definite = new LinkedHashSet<>();
        for (Failure f : failures) {
            definite.addAll(faults(f.keys()));
        }
        // one unhandleable channel makes "restart and it converges" a lie for the whole screen, and it
        // outranks a recurrence: escalation may sharpen the wording, never change the diagnosis. The
        // consequence says nothing about what this is NOT, because a repairable channel can be listed in the rows
        // right above it and "not a package difference" would be disproved by the screen's own evidence
        if (definite.contains(Fault.TERMINAL)) {
            return new Bugcheck("BAMFOS ran into a problem it cannot handle.", "", rawRows(rawReason),
                    "A sync will not clear this on its own. " + CONTACT,
                    "", "BAMF_PROTOCOL_FAULT_NOT_HANDLED");
        }
        // same shape as terminal and for the same reason: the pack has no package to move here, so no CAPS
        // order and no recurrence escalation, because a game version never converges on a restart either
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
            // names only, NEVER the numbers: version.mismatch carries the PAYLOAD protocol version
            // (NetworkComponentNegotiator:175 passes left.version(), right.version()), so printing them
            // sends someone hunting for a JourneyMap 2.0 that does not exist
            case SKEW -> new Bugcheck("Client package revision mismatch detected.",
                    "Packages at a different revision:", names,
                    "Your copy of these is not the revision the server is running.", RESTART,
                    "BAMF_PACKAGE_REVISION_MISMATCH");
            default -> new Bugcheck("Client and server do not agree on the pack contents.",
                    names.isEmpty() ? "" : "Packages involved:", names,
                    "The next boot syncs your client to the server.", RESTART, "BAMF_SYNC_REQUIRED");
        };
        if (names.isEmpty()) {
            // no channel named a package, so the raw text IS the specifics; a list header over a line of
            // NeoForge prose would label it as a package name
            base = new Bugcheck(base.diagnosis, "", rawRows(rawReason), base.consequence, base.action,
                    base.stopCode);
        }
        return recurredUnchanged ? converged(base) : base;
    }

    /** The anti-loop variant: same shape, same rows, and it says the honest thing. Not "cannot repair"
     *  and not "cannot handle", because BAMFOS knows exactly what this is, it just did not converge. */
    private static Bugcheck converged(Bugcheck base) {
        return new Bugcheck("This is the same mismatch as the last attempt, and the last sync changed nothing.",
                base.listHeader, base.rows,
                "Restarting again will not clear it on its own. " + CONTACT,
                "", "BAMF_SYNC_CONVERGENCE_FAILED");
    }

    /**
     * The same screen with the CAPS restart order taken off, for the boot where the breadcrumb could not be
     * written. That file IS the change the order promises: with no crumb the next boot quarantines nothing and
     * DisconnectHistory never gets the entry the recurrence escalation reads, so the player would restart into
     * this exact screen forever. body() drops an empty action structurally, so the consequence becomes the
     * instruction. A variant that already has no order keeps its own wording, because it already says contact.
     */
    public Bugcheck unrecorded() {
        if (action.isEmpty()) {
            return this;
        }
        return new Bugcheck(diagnosis, listHeader, rows,
                "BAMFOS could not record this kick, so a restart cannot act on it. " + CONTACT,
                "", stopCode);
    }

    /**
     * The sample kicks behind BAMFMENU_BSOD_DEMO and the offline renderer, one source so what the pack host
     * eyeballs offline is what the env var puts on screen. Every sample carries the REAL NeoForge key and
     * the real reason text the live path would classify on, so a sample cannot drift into a shape the
     * classifier never produces.
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
            // the arg is the SERVER's game version, so this is what a player on a stale profile reads
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

    /** EVERY fault in the chain, not the first one that classifies. A wrapper key contributes nothing, so
     *  scanning the whole chain beats trusting one arg index, and a chain that classifies nothing degrades to
     *  a sync, NEVER to cannot-handle.
     *  IMPORTANT: one reason really can carry two opposite faults. NeoForge appends BOTH data-map directions
     *  into ONE disconnect component when mandatory data maps differ each way (ClientRegistryManager's
     *  handleKnownDataMaps builds missing_our and missing_their as siblings of one empty root), and a
     *  first-match read printed "These packages are not present on the server." over a row saying the server
     *  had ones we did not. Both in the set means neither is the whole story, so the mixed-fault default is
     *  what renders. */
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

    /** The kick text as rows, one per line it already had. */
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

    /** The screen body in draw order. An empty header or action drops its block entirely, so the pack's
     *  rule holds structurally: no list header without names, and no CAPS line on a screen where a
     *  restart would not help. */
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

    /** Every body line as one string, for a test that has to prove a wording is ABSENT. */
    public String text() {
        StringBuilder b = new StringBuilder();
        for (Block block : body()) {
            b.append(block.text()).append('\n');
        }
        return b.toString();
    }

    /**
     * The body trimmed to a window that cannot hold it, shedding decoration before content: the breathing
     * room, then the "Technical information:" label (its payload, the STOP line, stays), then the tail of
     * the list, then the frame sentence, then the softer paragraphs. The diagnosis, the instruction and
     * the STOP code never go, because a bugcheck a player cannot quote is worse than a cramped one.
     * `lines` measures a block at the caller's real font and column, so one ladder covers every GUI scale.
     */
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
            // on a screen with no CAPS action line the consequence IS the instruction (contact the pack host,
            // send the log), so there it outlives even the package rows
            if (shed == Role.CONSEQUENCE && kept.stream().noneMatch(b -> b.role() == Role.ACTION)) {
                continue;
            }
            kept.removeIf(b -> b.role() == shed);
        }
        return kept;
    }

    /** Drop rows off the end until the list fits, then say how many went. Two things keep that count honest.
     *  The reserve is only taken once a drop has HAPPENED, because a body that lands exactly on budget after the
     *  TECH shed would otherwise trade a real culprit for a pointless "+1 more". And a remainder cap() already
     *  wrote is picked up as a count instead of being shed as an ordinary row: it sits last, so it is the first
     *  thing this loop reaches, and dropping it replaced the true number of hidden culprits with a smaller one. */
    private static void collapseRows(List<Block> kept, ToIntFunction<Block> lines, int budget) {
        int dropped = takeRemainder(kept);
        while (rowCount(kept) > 1 && height(kept, lines) + (dropped > 0 ? 1 : 0) > budget) {
            dropped += removeLastRow(kept);
        }
        if (dropped > 0) {
            kept.add(lastRow(kept) + 1, new Block(Role.ROW, "+" + dropped + " more", ROW_INDENT));
        }
    }

    /** cap()'s own overflow row, taken off the list and handed back as a number. cap() is the only writer of
     *  this shape, and a kick line that reads exactly `+<digits> more` is not a shape NeoForge produces. */
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

package com.retro.bamfmenu.early.core.repair.items.server;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;

// Points both the updater config and Minecraft's servers.dat at a new server address so you don't end up updating fine while never joining.
public final class ServerItem implements RepairDef {

    /** Placed after the rollback picker near the end of the menu since it's rarely used. */
    @Override
    public int order() {
        return 65;
    }

    @Override
    public RepairItem item() {
        return RepairItem.edit(
                "server",
                "Change Server",
                "Point this install at a different server address. Updates where the pack checks for "
                        + "updates and the entry in your server list, and leaves your other servers alone.",
                RepairGate.FILE_SAFE,
                state -> state.serverCell(),
                "New server address (host or host:port)",
                UpdaterState::serverCell,
                (ops, typed) -> apply(ops, typed));
    }

    /**
     * Splits typed input into host and optional port, then passes it to the engine.
     * Splits from the right to preserve IPv6 colons, and rejects invalid ports instead of defaulting to 25565.
     */
    private static RepairResult apply(com.retro.bamfmenu.early.core.repair.api.RepairOps ops, String typed) {
        String host = typed;
        int port = 25565;
        int colon = typed.lastIndexOf(':');
        boolean bracketed = typed.startsWith("[") && typed.indexOf(']') > 0;
        if (colon > 0 && (!bracketed || colon > typed.indexOf(']'))) {
            String tail = typed.substring(colon + 1);
            try {
                port = Integer.parseInt(tail.trim());
            } catch (NumberFormatException notAPort) {
                return new RepairResult(RepairResult.Kind.REFUSED,
                        "\"" + tail + "\" is not a port number.");
            }
            host = typed.substring(0, colon);
        }
        return describe(ops.setServer(host, port), host, port);
    }

    /* Maps the four setServer return codes to status lines; code 3 has a separate message
       so a partial update is never reported as "Saved" when servers.dat still has the old address. */
    private static RepairResult describe(int code, String host, int port) {
        String where = port == 25565 ? host : host + ":" + port;
        return switch (code) {
            case 0 -> new RepairResult(RepairResult.Kind.DONE,
                    "Now pointed at " + where + ". Restart to connect.");
            case 1 -> new RepairResult(RepairResult.Kind.REFUSED, "Already pointed at " + where + ".");
            case 3 -> new RepairResult(RepairResult.Kind.FAILED,
                    "Updates now come from " + where + ", but your server list could not be changed. "
                            + "Edit the entry by hand to join.");
            default -> new RepairResult(RepairResult.Kind.FAILED, "Could not change the server address.");
        };
    }
}

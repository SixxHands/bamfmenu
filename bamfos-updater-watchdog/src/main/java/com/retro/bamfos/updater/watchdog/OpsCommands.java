package com.retro.bamfos.updater.watchdog;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

// Brigadier wiring and op permission gate for /bamfos. All reply logic lives in OpsBrain so it stays testable.
final class OpsCommands {

    /** Permission level 2 (standard op level; the server console runs at level 4). */
    private static final int OP_LEVEL = 2;

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, BamfosWatchdog watchdog) {
        dispatcher.register(Commands.literal("bamfos")
                .requires(src -> src.hasPermission(OP_LEVEL))
                .executes(ctx -> reply(ctx.getSource(), OpsBrain.Reply.ok(OpsBrain.help())))
                .then(Commands.literal("help")
                        .executes(ctx -> reply(ctx.getSource(), OpsBrain.Reply.ok(OpsBrain.help()))))
                .then(Commands.literal("status")
                        .executes(ctx -> reply(ctx.getSource(), watchdog.opStatus())))
                .then(Commands.literal("reload")
                        .executes(ctx -> reply(ctx.getSource(), watchdog.opReload())))
                .then(Commands.literal("check")
                        .executes(ctx -> later(ctx.getSource(), watchdog, watchdog::opCheck, OpsBrain.CHECK_STARTED)))
                .then(Commands.literal("sync")
                        .executes(ctx -> later(ctx.getSource(), watchdog, watchdog::opSync, OpsBrain.SYNC_STARTED)))
                .then(Commands.literal("restart")
                        .then(Commands.literal("now")
                                .executes(ctx -> reply(ctx.getSource(), watchdog.opRestartNow())))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> reply(ctx.getSource(), watchdog.opRestartCancel()))))
                .then(Commands.literal("cleartoken")
                        .executes(ctx -> reply(ctx.getSource(), watchdog.opClearToken()))));
    }

    /**
     * Offloads GitHub work to the poll thread and prints an immediate ack so slow network I/O never stalls
     * the server thread or leaves the operator staring at silence.
     */
    private static int later(CommandSourceStack src, BamfosWatchdog watchdog,
                             Supplier<OpsBrain.Reply> work, String ack) {
        if (!watchdog.offload(work, reply -> reply(src, reply))) {
            return reply(src, OpsBrain.Reply.bad(OpsBrain.NO_WORKER));
        }
        return reply(src, OpsBrain.Reply.ok(ack));
    }

    /**
     * Catches failures at the command boundary so async callbacks (e.g. a player who logged off mid-poll)
     * do not throw Brigadier stack traces.
     */
    private static int reply(CommandSourceStack src, OpsBrain.Reply r) {
        try {
            for (String line : r.lines()) {
                if (r.failed()) {
                    src.sendFailure(Component.literal("[BAMFOS] " + line));
                } else {
                    src.sendSuccess(() -> Component.literal("[BAMFOS] " + line), false);
                }
            }
        } catch (Throwable gone) {
            // can't send a reply when the command source itself threw
        }
        return r.failed() ? 0 : 1;
    }

    private OpsCommands() {
    }
}

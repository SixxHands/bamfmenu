package com.retro.bamfrestore;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// /bamfrestore list | use <file> | status | cancel. Level 4, this is a world-eating lever so pull it from console.
// "use" only arms it for next restart.
public final class RestoreCommand {

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bamfrestore")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("cancel")
                        .executes(ctx -> cancel(ctx.getSource())))
                .then(Commands.literal("use")
                        // greedy string so world/<name>.zip paths don't choke on the slash
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    try {
                                        Path dir = gameDir().resolve(BootRestore.BACKUP_DIR);
                                        for (Path p : BootRestore.listBackups(gameDir())) {
                                            builder.suggest(relative(dir, p));
                                        }
                                    } catch (IOException ignored) {
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> use(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file").trim())))));
    }

    private static int list(CommandSourceStack src) {
        try {
            List<Path> backups = BootRestore.listBackups(gameDir());
            if (backups.isEmpty()) {
                return fail(src, "no backups in " + BootRestore.BACKUP_DIR + "/ (checked one subdir deep too)");
            }
            Path dir = gameDir().resolve(BootRestore.BACKUP_DIR);
            ok(src, backups.size() + " backup(s), newest first:");
            for (Path p : backups.subList(0, Math.min(10, backups.size()))) {
                long mb = p.toFile().length() / (1024 * 1024);
                long mins = Duration.between(
                        Instant.ofEpochMilli(p.toFile().lastModified()), Instant.now()).toMinutes();
                ok(src, "  " + relative(dir, p) + "  " + mb + " MB, " + age(mins) + " old");
            }
            return 1;
        } catch (IOException e) {
            return fail(src, "could not list backups: " + e.getMessage());
        }
    }

    private static int use(CommandSourceStack src, String fileName) {
        String failure = BootRestore.arm(gameDir(), fileName);
        if (failure != null) {
            return fail(src, failure);
        }
        ok(src, "rollback ARMED for " + fileName);
        ok(src, "it applies on the NEXT restart, before the world loads. the current world gets "
                + "parked, not deleted. /bamfrestore cancel to disarm");
        return 1;
    }

    private static int status(CommandSourceStack src) {
        Path marker = gameDir().resolve(BootRestore.MARKER);
        try {
            if (Files.isRegularFile(marker)) {
                ok(src, "ARMED: " + Files.readString(marker).trim() + " applies on the next restart");
            } else {
                ok(src, "nothing armed. /bamfrestore list to pick a backup");
            }
            Path last = gameDir().resolve(BootRestore.MARKER_LAST);
            if (Files.isRegularFile(last)) {
                ok(src, "last rollback: " + Files.readString(last).trim());
            }
            Path failed = gameDir().resolve(BootRestore.MARKER_FAILED);
            if (Files.isRegularFile(failed)) {
                ok(src, "a previous attempt stood down: " + Files.readString(failed).trim());
            }
            return 1;
        } catch (IOException e) {
            return fail(src, "could not read the marker: " + e.getMessage());
        }
    }

    private static int cancel(CommandSourceStack src) {
        try {
            if (Files.deleteIfExists(gameDir().resolve(BootRestore.MARKER))) {
                ok(src, "disarmed, the next restart boots the world as it is");
            } else {
                ok(src, "nothing was armed");
            }
            return 1;
        } catch (IOException e) {
            return fail(src, "could not remove the marker: " + e.getMessage());
        }
    }

    private static String age(long mins) {
        return mins < 120 ? mins + " min" : mins < 2880 ? (mins / 60) + " h" : (mins / 1440) + " d";
    }

    private static String relative(Path backupDir, Path zip) {
        return backupDir.relativize(zip).toString().replace('\\', '/');
    }

    private static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    private static void ok(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal("[BAMFRESTORE] " + line), false);
    }

    private static int fail(CommandSourceStack src, String line) {
        src.sendFailure(Component.literal("[BAMFRESTORE] " + line));
        return 0;
    }

    private RestoreCommand() {
    }
}

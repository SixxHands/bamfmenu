package com.retro.bamfos.updater.boot;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.api.distmarker.Dist;

import java.nio.file.Path;

/**
 * The client door's trigger: FML promotes this jar to the SERVICE layer (our services
 * file names IModFileCandidateLocator, which is on ModDirTransformerDiscoverer's promotion list)
 * and calls findCandidates during discovery -- the ONE moment before mods/ jars are opened and
 * locked. We locate nothing; we use the moment.
 * getPriority() >= 1000 is LOAD-BEARING: locators sort by priority descending and
 * ModsFolderLocator sits at 0 -- without this, the mods folder could be scanned and locked before
 * the sync runs. The dist gate is REQUIRED, not belt-and-braces: locator discovery runs identically
 * on dedicated servers, and a shim jar landing in a server's mods/ WOULD execute here without it.
 * at the boundary: nothing thrown from here may ever stop a boot.
 */
public final class ClientLocator implements IModFileCandidateLocator {

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public void findCandidates(net.neoforged.neoforgespi.ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                return;
            }
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path engineJar = gameDir.resolve(".bamfos-updater").resolve("engine").resolve("bamfos-updater-engine.jar");
            EngineLoader.Engine engine = EngineLoader.ensureAndLoad(engineJar, EngineLoader.embeddedBaseline());
            engine.enter(new String[]{"--client", "--dir", gameDir.toString()});
        } catch (Throwable t) {
            System.out.println("[updater] sync skipped: " + t); // Never block the game from booting.
        }
    }

    @Override
    public String toString() {
        return "bamfos-updater client door";
    }
}

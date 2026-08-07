package com.retro.bamfos.updater.boot;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/* FML service locator hook on client boot. We don't locate mods, we just hijack the moment before mods/
 * jars get opened and locked. Priority >= 1000 keeps us ahead of ModsFolderLocator (0), and the client
 * dist check stops this from running if the jar lands in a server's mods/ folder. */
public final class ClientLocator implements IModFileCandidateLocator {

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                CoreSlot.clientBoot(FMLPaths.GAMEDIR.get());
            }
        } catch (Throwable t) {
            System.out.println("[updater] sync skipped: " + t); // Never block the game from booting.
        }
    }

    @Override
    public String toString() {
        return "bamfos-updater client door";
    }
}

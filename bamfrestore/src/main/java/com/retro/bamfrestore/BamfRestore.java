package com.retro.bamfrestore;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;

// Pending rollbacks have to run here in the constructor before anything opens the world or locks region files.
@Mod(BamfRestore.MODID)
public final class BamfRestore {

    public static final String MODID = "bamfrestore";

    private static final Logger LOGGER = LogUtils.getLogger();

    public BamfRestore(IEventBus modBus, ModContainer container) {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return; // server-only mod; do nothing if loaded on a client
        }
        BootRestore.applyPendingIfAny(FMLPaths.GAMEDIR.get());
        NeoForge.EVENT_BUS.addListener(RestoreCommand::onRegisterCommands);
        LOGGER.info("[BAMFRESTORE] listening: /bamfrestore");
    }
}

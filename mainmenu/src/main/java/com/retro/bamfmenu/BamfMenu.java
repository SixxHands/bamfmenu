package com.retro.bamfmenu;

import com.mojang.logging.LogUtils;
import com.retro.bamfmenu.client.EarlyWindowKeying;
import com.retro.bamfmenu.client.KickWatch;
import com.retro.bamfmenu.client.QuitWatchdog;
import com.retro.bamfmenu.client.WindowIconHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

// Client-only mod for custom window chrome, splashes, Win95 loading screen, mismatch BSOD, and quit watchdog.
@Mod(value = BamfMenu.MOD_ID, dist = Dist.CLIENT)
public final class BamfMenu {

    public static final String MOD_ID = "bamfmenu";

    private static final Logger LOGGER = LogUtils.getLogger();

    public BamfMenu(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::onClientSetup);
        // mismatch blue screen: screen-open interception is a GAME-bus event
        NeoForge.EVENT_BUS.addListener(KickWatch::onScreenOpening);
        // quit watchdog: the dev JVM has been seen outliving a normal quit, the GAME-bus shutdown event arms it
        NeoForge.EVENT_BUS.addListener(QuitWatchdog::onGameShuttingDown);
        LOGGER.info("[bamfmenu] client cosmetic mod loaded");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        /* Self-heal the early-window keying for the NEXT launch (config IO; runs on the setup thread, off
           the render thread, no enqueueWork). FML already read fml.toml at boot, so this can't change
           the current window, only ensure the next boot uses our BIOS terminal. */
        EarlyWindowKeying.ensureProvider();
        /* enqueueWork runs on the main render thread once the GLFW window is live. */
        event.enqueueWork(WindowIconHandler::applyRandomIcon);
    }
}

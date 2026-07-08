package com.retro.bamfmenu;

import com.mojang.logging.LogUtils;
import com.retro.bamfmenu.client.EarlyWindowKeying;
import com.retro.bamfmenu.client.WindowIconHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/* BAMF: Menu is a client-only cosmetic mod that dresses up the game window and boot experience:
   a random window icon, a custom window/taskbar name, a static main-menu background, custom splash
   text with per-line colors, and the NeoForge loading screens reskinned as an old-school terminal.
   It is purely visual and registers nothing on the server, so the whole mod is gated to the physical
   client via @Mod(dist = Dist.CLIENT). This scaffold only logs that it loaded; the cosmetic
   features arrive in later phases (see docs/BAMF_MENU_PLAN.md).

   wired here: the static menu background + custom splashes are mixin-driven and need no
   registration; the random window icon is applied once at client setup, after the window exists. */
@Mod(value = BamfMenu.MOD_ID, dist = Dist.CLIENT)
public final class BamfMenu {

    public static final String MOD_ID = "bamfmenu";

    private static final Logger LOGGER = LogUtils.getLogger();

    public BamfMenu(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::onClientSetup);
        LOGGER.info("[bamfmenu] client cosmetic mod loaded");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        /* Self-heal the early-window keying for the NEXT launch (config IO; runs on the setup thread, off
           the render thread -- no enqueueWork). FML already read fml.toml at boot, so this can't change
           the current window, only ensure the next boot uses our BIOS terminal. */
        EarlyWindowKeying.ensureProvider();
        /* enqueueWork runs on the main render thread once the GLFW window is live. */
        event.enqueueWork(WindowIconHandler::applyRandomIcon);
    }
}

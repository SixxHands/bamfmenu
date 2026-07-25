package com.retro.bamfmenu.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The XP blue screen: full-screen override of a pack-server mismatch kick. Renders
 * theater and takes exactly two keys -- ESC back to the title screen (singleplayer stays available),
 * ENTER closes the game cleanly, exit 0 (Minecraft.stop() is the title-screen Quit path; the fix
 * waits for the next boot either way). Never auto-reopens: launcher-supervised processes clean up
 * LWJGL natives when their child exits, a self-respawn can find them deleted.
 * All wording owner-tunable.
 */
public final class BsodScreen extends Screen {

    private static final int XP_BLUE = 0xFF0000AA;

    private final String reason;
    private final boolean unrepairable;

    public BsodScreen(String reason, boolean unrepairable) {
        super(Component.literal("BAMFOS"));
        this.reason = reason;
        this.unrepairable = unrepairable;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, XP_BLUE);
        int x = Math.max(16, this.width / 10);
        int wrap = this.width - x * 2;
        int y = Math.max(24, this.height / 8);
        int lh = this.font.lineHeight + 2;

        List<String> head = new ArrayList<>();
        if (unrepairable) {
            head.add("YOUR DEVICE RAN INTO A PROBLEM IT CANNOT REPAIR.");
        } else {
            head.add("A problem has been detected and BAMFOS has been shut down to prevent damage");
            head.add("to your computer.");
        }
        for (String s : head) {
            g.drawString(this.font, s, x, y, 0xFFFFFFFF, false);
            y += lh;
        }
        y += lh;

        // the reason block is capped so the ESC/ENTER footer can NEVER be pushed off screen --
        // a mismatch naming 30 channels matters less than the player knowing how to leave
        List<FormattedCharSequence> lines = this.font.split(Component.literal(reason), wrap);
        int maxReasonRows = Math.max(3, (this.height - y - lh * 8) / lh);
        for (int i = 0; i < Math.min(lines.size(), maxReasonRows); i++) {
            g.drawString(this.font, lines.get(i), x, y, 0xFFFFFFFF, false);
            y += lh;
        }
        if (lines.size() > maxReasonRows) {
            g.drawString(this.font, "... (" + (lines.size() - maxReasonRows) + " more, see the log)", x, y, 0xFFFFFFFF, false);
            y += lh;
        }
        y += lh;

        if (unrepairable) {
            g.drawString(this.font, "Contact the pack owner, or check SETUP > OPTIONAL MODS.", x, y, 0xFFFFFFFF, false);
            y += lh * 2;
        } else {
            g.drawString(this.font, "RESTART YOUR GAME SO THESE CHANGES CAN BE APPLIED.", x, y, 0xFFFFFFFF, false);
            y += lh * 2;
            g.drawString(this.font, "Technical information:", x, y, 0xFFFFFFFF, false);
            y += lh * 2;
            g.drawString(this.font, "*** STOP: BAMF_SYNC_REQUIRED", x, y, 0xFFFFFFFF, false);
            y += lh * 2;
        }

        g.drawString(this.font, "Press ESC to return to the menu.  Press ENTER to close the game.",
                x, Math.max(y, this.height - lh * 3), 0xFFFFFFFF, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.minecraft.setScreen(new TitleScreen()); // NEVER setScreen(null) on a disconnect path
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.minecraft.stop(); // the title-screen Quit path: loop exits, normal teardown, exit 0
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // ESC is OURS (menu), the default would pop to a null screen mid-disconnect
    }
}

package com.retro.bamfmenu.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

// Full-screen BSOD override for pack mismatch kicks (wording lives in Bugcheck). ESC restores the original disconnect screen.
public final class BsodScreen extends Screen {

    private static final int BSOD_BLUE = 0xFF0000AA;
    private static final int BODY = 0xFFFFFFFF;
    private static final int BOX_FACE = 0xFFC0C0C0; // 9x silver
    private static final int BOX_EDGE = 0xFF808080;
    private static final int BOX_TEXT = 0xFF000000;

    private final Bugcheck check;
    // Original disconnect screen restored on ESC. Never init()'d before the swap to get it to start/run on normal path.
    private final Screen back;

    public BsodScreen(Bugcheck check, Screen back) {
        super(Component.literal("BAMFOS"));
        this.check = check;
        this.back = back;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BSOD_BLUE);
        int lh = this.font.lineHeight + 2;

        // the centered gray BAMFOS box, the way 9x framed "Windows"
        String tag = "BAMFOS";
        int boxW = this.font.width(tag) + 12;
        int boxH = this.font.lineHeight + 6;
        int boxX = (this.width - boxW) / 2;
        int boxY = Math.max(lh * 2, this.height / 6);
        g.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + boxH + 1, BOX_EDGE);
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, BOX_FACE);
        g.drawString(this.font, tag, boxX + 6, boxY + 3, BOX_TEXT, false);

        // body: a centered column, text left-aligned, every paragraph wrapped so it can't run off
        int colW = Math.min(this.width - 40, 460);
        int left = (this.width - colW) / 2;
        int top = boxY + boxH + lh * 2;
        // keep the two exit keys pinned at the bottom; give the body whatever height is left above them
        // and let Bugcheck.fit drop anything that doesn't fit
        int exitsY = Math.max(top + lh * 2, this.height - lh * 3);
        int y = top;
        for (Bugcheck.Block block : Bugcheck.fit(check.body(), b -> wrap(b, colW), (exitsY - top) / lh)) {
            y = para(g, block.text(), left + block.indent(), y, colW - block.indent(), lh);
        }

        g.drawString(this.font, Bugcheck.EXIT_ESC, left, exitsY, BODY, false);
        g.drawString(this.font, Bugcheck.EXIT_ENTER, left, exitsY + lh, BODY, false);
    }

    /** Number of wrapped lines a block takes at this column width, used by Bugcheck.fit. */
    private int wrap(Bugcheck.Block block, int colW) {
        return block.text().isEmpty() ? 1
                : this.font.split(Component.literal(block.text()), colW - block.indent()).size();
    }

    /** One left-aligned paragraph wrapped to the column; returns the y just past it. */
    private int para(GuiGraphics g, String text, int left, int y, int colW, int lh) {
        if (text.isEmpty()) {
            return y + lh;
        }
        for (FormattedCharSequence line : this.font.split(Component.literal(text), colW)) {
            g.drawString(this.font, line, left, y, BODY, false);
            y += lh;
        }
        return y;
    }

    // Swallow all inputs so injected mod widgets can't be clicked behind the BSOD; only ESC/Enter exit.
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            KickWatch.restore(this.back);
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.minecraft.stop(); // the title-screen Quit path: loop exits, normal teardown, exit 0
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return true; // setScreen calls KeyMapping.releaseAll on the way in and out, so nothing sticks
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // keyPressed doesn't call super right now, but keep this false so ESC won't fall back to popGuiLayer
        // if super is ever called later
        return false;
    }

    @Override
    public void onClose() {
        // the Screen default is popGuiLayer, which on an empty layer stack is setScreen(null), and that
        // throws IllegalStateException while a level is tearing down
        KickWatch.restore(this.back);
    }
}

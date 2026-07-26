package com.retro.bamfmenu.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * The blue screen: full-screen override of a pack-server mismatch kick, framed like a
 * classic Win9x BSOD over an NT bugcheck body. A dead end on purpose, every key and click is swallowed
 * and the only way out is ESC, which hands back the exact vanilla/NeoForge disconnect screen this
 * replaced, the one still carrying the channel table and its own log and mods-folder buttons.
 * Never auto-reopens: a launcher cleans up LWJGL natives when its child exits, so a self-respawn can
 * find them gone and hard-crash. All wording lives in {@link Bugcheck}.
 */
public final class BsodScreen extends Screen {

    private static final int BSOD_BLUE = 0xFF0000AA;
    private static final int BODY = 0xFFFFFFFF;
    private static final int BOX_FACE = 0xFFC0C0C0; // 9x silver
    private static final int BOX_EDGE = 0xFF808080;
    private static final int BOX_TEXT = 0xFF000000;

    private final Bugcheck check;
    /** The screen this one displaced, handed back on ESC. Never init()ed and never removed(), because
     *  setScreen posts Opening BEFORE it touches either, so re-showing it runs the normal first-show
     *  path and ModMismatchDisconnectedScreen's ctor does not fire twice. */
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
        // the two exit keys are bottom-pinned and are the last thing allowed to fall off the screen, so
        // the body gets whatever is left above them and Bugcheck.fit sheds the rest
        int exitsY = Math.max(top + lh * 2, this.height - lh * 3);
        int y = top;
        for (Bugcheck.Block block : Bugcheck.fit(check.body(), b -> wrap(b, colW), (exitsY - top) / lh)) {
            y = para(g, block.text(), left + block.indent(), y, colW - block.indent(), lh);
        }

        g.drawString(this.font, Bugcheck.EXIT_ESC, left, exitsY, BODY, false);
        g.drawString(this.font, Bugcheck.EXIT_ENTER, left, exitsY + lh, BODY, false);
    }

    /** Physical lines one block costs at this column, which is what the fit ladder budgets against. */
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

    /* Every input override returns true, handled, including the ones we ignore. NeoForge lets any mod push
       a widget into any screen's children list through ScreenEvent.Init, and the Screen defaults would
       happily click, focus and type into it. render() never walks renderables, so an injected widget is
       invisible here, and an invisible button that still takes clicks is exactly the "it opened some other
       screen while I was clicking around" bug. Swallow it all, exit on two keys. */
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
        // keyPressed never reaches super, so nothing consults this today; it stays flipped so an edit that
        // DOES call super cannot hand ESC back to the default popGuiLayer path
        return false;
    }

    @Override
    public void onClose() {
        // the Screen default is popGuiLayer, which on an empty layer stack is setScreen(null), and that
        // throws IllegalStateException while a level is tearing down
        KickWatch.restore(this.back);
    }
}

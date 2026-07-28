"""Offline render of the disconnect BLUE SCREEN, the one screen a camera cannot reach.
USAGE: python tools/bsod_render.py <outdir>
EXIT: 0 = rendered, 1 = bad args, 2 = the Java dump failed.
REQUIRES: pillow, fonttools (same as repair_render, whose font machinery this imports).

Everything else in assets/ is a real screenshot now (tools/shoot.py). This one cannot be: BsodScreen is a
Minecraft Screen in the MOD jar, drawn after the game is up and only when the pack's own server refuses a
join, so there is no early window to photograph and no way to pose the kick without a server. What it CAN
do is ask the shipped Bugcheck for its blocks, which is where every word on that screen lives.

This file used to render the POST screens too. It was wrong about all of them, in ways nobody could see
without the real thing next to it: no rule line under the banner, no dmesg row, no bottom status bar, no
BIOS ID footer, and a CPU line in a format the provider never produces. Those are screenshots now. Do not
reintroduce a hand-built mirror of a screen that can simply be photographed.
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import repair_render as rr  # noqa: E402  (path juggling has to come first)

REPO = Path(__file__).resolve().parents[1]
MOD_SRC = REPO / "src/main/java"

BSOD_BLUE = (0x00, 0x00, 0xAA)
BSOD_BODY = (0xFF, 0xFF, 0xFF)
BOX_FACE = (0xC0, 0xC0, 0xC0)
BOX_TEXT = (0x00, 0x00, 0x00)

DUMP = r"""
import com.retro.bamfmenu.client.Bugcheck;
import java.util.List;

public class BsodDump {
    public static void main(String[] a) {
        Bugcheck b = Bugcheck.of(
                List.of(new Bugcheck.Failure("cobblemon", List.of()),
                        new Bugcheck.Failure("bamfnether", List.of())),
                "Incompatible client! Missing: cobblemon, bamfnether (+3 more)", false);
        for (Bugcheck.Block blk : b.body()) {
            System.out.println("@@B\t" + blk.role() + "\t" + blk.indent() + "\t" + blk.text());
        }
    }
}
"""


def java_dump():
    """javac the SHIPPED Bugcheck plus a throwaway main, then read its stdout. Same shape as
    repair_render.dump: compile what ships, never a copy of it."""
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        (td / "BsodDump.java").write_text(DUMP, encoding="utf-8")
        out = td / "classes"
        out.mkdir()
        r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out),
                            "-sourcepath", str(MOD_SRC), str(td / "BsodDump.java")],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stderr)
            sys.exit(2)
        r = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                            "-cp", str(out), "BsodDump"], capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stderr)
            sys.exit(2)
        return r.stdout.splitlines()


def render(blocks, out):
    w, h = 1600, 900
    f = ImageFont.truetype(str(rr.FONT_FILE), rr.FACE_SIZE)
    img = Image.new("RGB", (w, h), BSOD_BLUE)
    d = ImageDraw.Draw(img)

    # the 9x silver BAMFOS box, centred, the one thing on this screen that is not blue and white
    label = " BAMFOS "
    bx = (w - len(label) * rr.CHAR_WIDTH) // 2
    by = rr.LINE_SPACING * 3
    d.rectangle([bx - 6, by - 4, bx + len(label) * rr.CHAR_WIDTH + 4, by + rr.LINE_SPACING], fill=BOX_FACE)
    rr.gtext(d, bx, by, label, f, BOX_TEXT)

    y = by + rr.LINE_SPACING * 3
    left = rr.MARGIN * 3
    cols = (w - left * 2) // rr.CHAR_WIDTH
    for b in blocks:
        if b["role"] == "GAP":
            y += rr.LINE_SPACING
            continue
        text, indent = b["text"], b["indent"]
        room = max(20, cols - indent)
        while text:  # the screen wraps a paragraph; so does this
            rr.gtext(d, left + indent * rr.CHAR_WIDTH, y, text[:room], f, BSOD_BODY)
            text = text[room:]
            y += rr.LINE_SPACING
    img.crop((0, 0, w, min(h, y + rr.LINE_SPACING * 3))).save(out)
    print(f"  bsod-mismatch.png  ({len(blocks)} blocks)")


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 1
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    blocks = []
    for ln in java_dump():
        if ln.startswith("@@B\t"):
            _, role, indent, text = ln.split("\t", 3)
            blocks.append({"role": role, "indent": int(indent), "text": text})
    if not blocks:
        print("  !! the bugcheck produced no blocks")
        return 2
    render(blocks, out / "bsod-mismatch.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())

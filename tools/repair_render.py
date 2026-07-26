"""Offline preview of the UPDATE AND REPAIR window: every realistic state, no Minecraft launch.
USAGE: python tools/repair_render.py <outdir> [--window WxH ...]
EXIT: 0 = rendered and no geometry problem, 1 = bad args, 2 = the Java dump failed, 3 = a row collided.

The strings are the REAL ones: this javac-compiles the SHIPPED core/repair classes plus RepairEntry and
asks RepairSample.session(scenario).frame(...) for each state, so no line here can drift from what the
game draws. Only the GEOMETRY is mirrored (the banner canvas math from drawHeader/headerBottomBodyY, the
box column offsets from RepairScreen.render), because measuring glyphs needs the baked font, and the
banner art is loaded from the same ui/*.txt the jar ships. The gate at the end is the thing a screenshot
cannot tell you: whether the box clears the bottom control bar at that window shape.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[1]   # tools/ lives at the repo root, so this runs from a clone
SRC = REPO / "earlywindow/src/main/java"
ART = REPO / "earlywindow/src/main/resources/ui"
FONT = r"C:\Windows\Fonts\consola.ttf"

# --- constants lifted VERBATIM from the Java (keep in sync) ---
LOGICAL_W = 1400        # TerminalWindowProvider.LOGICAL_W
MARGIN = 16             # TerminalWindowProvider.MARGIN
HEADER_FW = 0.42        # TerminalWindowProvider.HEADER_FW
LINE_SPACING = 28       # font.lineSpacing() at runtime
CHAR_WIDTH = 14         # font.stringWidth("0") at runtime
LEFT_INNER = 36         # RepairBox.LEFT_INNER
RESERVED_ROWS = 12      # SETUP_OPTIONS.length, what RepairScreen reserves

BG = (10, 10, 12)
TONES = {
    "NORMAL": (0xAA, 0xAA, 0xAA),
    "BRIGHT": (0xFF, 0xFF, 0xFF),
    "DIM": (0x6E, 0x6E, 0x6E),
    "NOTICE": (0xFF, 0xD7, 0x00),
    "ALERT": (0xFF, 0x30, 0x30),
}

DUMPER = """
import com.retro.bamfmenu.early.bios.modes.repair.RepairEntry;
import com.retro.bamfmenu.early.core.repair.session.RepairSample;
import com.retro.bamfmenu.early.core.repair.session.RepairSession;
import com.retro.bamfmenu.early.core.repair.view.RepairFrame;

public class Dump {
    public static void main(String[] args) {
        int maxChars = Integer.parseInt(args[0]);
        int rows = Integer.parseInt(args[1]);
        for (String scenario : RepairSample.SCENARIOS) {
            RepairSession s = RepairSample.session(scenario);
            RepairFrame f = s.frame(maxChars, rows, 0);
            System.out.println("S\\t" + scenario);
            System.out.println("T\\t" + f.title());
            System.out.println("A\\t" + f.topBar());
            for (RepairFrame.Row r : f.rows()) {
                System.out.println("R\\t" + r.leftTone() + "\\t" + r.left() + "\\t"
                        + r.rightTone() + "\\t" + r.right() + "\\t" + r.divider());
            }
            System.out.println("Z\\t" + f.bottomBar());
            System.out.println("H\\t" + f.hints());
            for (String p : f.prompt()) {
                System.out.println("P\\t" + p);
            }
        }
        String joke = "This is the first version, silly!";
        for (boolean present : new boolean[] {false, true}) {
            System.out.println("E\\t" + (present ? "present" : "absent")
                    + "\\t" + RepairEntry.label(RepairEntry.ROW_KEY, present)
                    + "\\t" + RepairEntry.help(RepairEntry.ROW_KEY, joke, present)
                    + "\\t" + RepairEntry.state(RepairEntry.ROW_KEY, "", present));
        }
    }
}
"""


def gtext(d, x, y, text, font, fill, cw=CHAR_WIDTH):
    """Draw on the CHARACTER GRID, one glyph per cell, the way RichFont's atlas does in game. PIL would
    otherwise use each glyph's own advance, and the box-drawing glyphs are not the same width as a digit
    in consola, which slides the top bar's junction away from the interior divider it has to line up with."""
    for i, ch in enumerate(text):
        if ch != " ":
            d.text((x + i * cw, y), ch, font=font, fill=fill)


def dump(max_chars, rows):
    """Compile the shipped window and read every posed frame out of it."""
    sources = sorted(str(p) for p in (SRC / "com/retro/bamfmenu/early/core/repair").rglob("*.java"))
    sources.append(str(SRC / "com/retro/bamfmenu/early/core/updater/OpsBridge.java"))
    sources.append(str(SRC / "com/retro/bamfmenu/early/bios/modes/repair/RepairEntry.java"))
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "Dump.java").write_text(DUMPER, encoding="utf-8")
        out = tmp / "classes"
        javac = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out),
                                "-sourcepath", str(SRC), *sources, str(tmp / "Dump.java")],
                               capture_output=True, text=True)
        if javac.returncode != 0:
            print(javac.stderr)
            sys.exit(2)
        # stdout.encoding as well as file.encoding: without it the box-drawing glyphs come back through
        # the pipe as '?' on a Windows console codepage, which looks exactly like a missing font glyph
        run = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                              "-cp", str(out), "Dump",
                              str(max_chars), str(rows)], capture_output=True, text=True,
                             encoding="utf-8")
        if run.returncode != 0:
            print(run.stderr)
            sys.exit(2)
    if run.stderr.strip():
        print("  java stderr: " + run.stderr.strip().replace("\n", " | "))
    frames, entry, cur = {}, {}, None
    for line in run.stdout.splitlines():
        f = line.split("\t")
        if f[0] == "S":
            cur = f[1]
            frames[cur] = {"title": "", "top": "", "rows": [], "bottom": "", "hints": "", "prompt": []}
        elif f[0] == "T":
            frames[cur]["title"] = f[1]
        elif f[0] == "A":
            frames[cur]["top"] = f[1]
        elif f[0] == "R":
            frames[cur]["rows"].append({"leftTone": f[1], "left": f[2], "rightTone": f[3],
                                        "right": f[4], "divider": f[5] == "true"})
        elif f[0] == "Z":
            frames[cur]["bottom"] = f[1]
        elif f[0] == "H":
            frames[cur]["hints"] = f[1]
        elif f[0] == "P":
            frames[cur]["prompt"].append(f[1] if len(f) > 1 else "")
        elif f[0] == "E":
            entry[f[1]] = {"label": f[2], "help": f[3], "state": f[4] if len(f) > 4 else ""}
    return frames, entry


def art(name, fallback):
    p = ART / name
    if not p.is_file():
        return fallback
    return [ln.rstrip("\n") for ln in p.read_text(encoding="utf-8").splitlines()]


def header_bottom(logical_w):
    """Mirror of TerminalWindowProvider.headerBottomBodyY: the banner is width-anchored, so this is
    linear in the body width and independent of the window's aspect."""
    bamf = art("bamfos.txt", ["B A M F O S"])
    sixx = art("sixxhands.txt", [])
    bw = max((len(l) for l in bamf), default=1) * CHAR_WIDTH / HEADER_FW
    y0 = LINE_SPACING * 2
    bottom = y0 + len(bamf) * LINE_SPACING
    if sixx:
        bottom = max(bottom, y0 + len(sixx) * LINE_SPACING)
    return int(bottom * logical_w / bw), bamf, sixx, bw


def widened(fb_w, fb_h):
    """Mirror of TerminalWindowProvider.setupBodyWidth, the short-window widen rule both BIOS screens
    size off (RepairScreen asks for it through BiosContext.biosBodyWidth)."""
    base = LOGICAL_W
    head, _, _, _ = header_bottom(base)
    def_h = base * fb_h // fb_w
    if head + (RESERVED_ROWS + 3) * LINE_SPACING <= def_h - 2 * LINE_SPACING:
        return base
    a = fb_h / fb_w
    frac = head / base
    if a <= frac:
        return base
    return int(max(base, -(-((RESERVED_ROWS + 5) * LINE_SPACING / (a - frac)) // 1)))


def render(scenario, frame, entry, fb_w, fb_h, out):
    logical_w = widened(fb_w, fb_h)
    logical_h = max(1, logical_w * fb_h // fb_w)
    max_chars = max(40, logical_w // CHAR_WIDTH - 1)
    head_y, bamf, sixx, banner_canvas = header_bottom(logical_w)

    img = Image.new("RGB", (logical_w, logical_h), BG)
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, 22)

    # the banner renders in its own wider canvas, so scale it back into body pixels like the shader does
    scale = logical_w / banner_canvas
    bf = ImageFont.truetype(FONT, max(6, int(22 * scale)))
    for i, line in enumerate(bamf):
        gtext(d, int(banner_canvas * 0.02 * scale), int((LINE_SPACING * 2 + i * LINE_SPACING) * scale),
              line, bf, TONES["NOTICE"], max(1, int(CHAR_WIDTH * scale)))
    for i, line in enumerate(sixx):
        w = max((len(l) for l in sixx), default=1) * CHAR_WIDTH
        gtext(d, int((banner_canvas - w - banner_canvas * 0.02) * scale),
              int((LINE_SPACING * 2 + i * LINE_SPACING) * scale), line, bf, TONES["NORMAL"],
              max(1, int(CHAR_WIDTH * scale)))

    gtext(d, MARGIN, head_y, "─" * max_chars, f, TONES["NORMAL"])
    title_y = head_y + LINE_SPACING
    gtext(d, MARGIN, title_y, frame["title"][:max_chars], f, TONES["BRIGHT"])

    box_top = title_y + LINE_SPACING
    legend_y = logical_h - LINE_SPACING
    left_cell = MARGIN + CHAR_WIDTH
    divider_x = MARGIN + (LEFT_INNER + 1) * CHAR_WIDTH
    right_cell = divider_x + CHAR_WIDTH
    right_inner = max(20, max_chars - LEFT_INNER - 3)
    right_x = MARGIN + (LEFT_INNER + right_inner + 2) * CHAR_WIDTH

    gtext(d, MARGIN, box_top, frame["top"], f, TONES["NORMAL"])
    for r, row in enumerate(frame["rows"]):
        ry = box_top + (r + 1) * LINE_SPACING
        gtext(d, MARGIN, ry, "│", f, TONES["NORMAL"])
        gtext(d, left_cell, ry, row["left"], f, TONES[row["leftTone"]])
        if row["divider"]:
            gtext(d, divider_x, ry, "├" + "─" * right_inner + "┤", f, TONES["NORMAL"])
        else:
            gtext(d, divider_x, ry, "│", f, TONES["NORMAL"])
            gtext(d, right_x, ry, "│", f, TONES["NORMAL"])
            gtext(d, right_cell, ry, row["right"], f, TONES[row["rightTone"]])
    box_bottom_y = box_top + (len(frame["rows"]) + 1) * LINE_SPACING
    gtext(d, MARGIN, box_bottom_y, frame["bottom"], f, TONES["NORMAL"])

    hints = frame["hints"][:max_chars]
    gtext(d, MARGIN + max(0, (logical_w // CHAR_WIDTH - len(hints)) // 2) * CHAR_WIDTH, legend_y,
              hints, f, TONES["DIM"])

    if frame["prompt"]:
        inner = max(4, max(len(l) for l in frame["prompt"]) + 4)
        bw, bh = (inner + 2) * CHAR_WIDTH, (len(frame["prompt"]) + 2) * LINE_SPACING
        px, py = max(MARGIN, (logical_w - bw) // 2), max(0, (logical_h - bh) // 2)
        d.rectangle([px, py, px + bw, py + bh], fill=BG)
        gtext(d, px, py, "┌" + "─" * inner + "┐", f, TONES["BRIGHT"])
        for i, line in enumerate(frame["prompt"]):
            pad = (inner - len(line)) // 2
            gtext(d, px, py + (i + 1) * LINE_SPACING, "│" + " " * pad + line, f,
                  TONES["NOTICE"] if i == len(frame["prompt"]) - 1 else TONES["BRIGHT"])
            gtext(d, px + (inner + 1) * CHAR_WIDTH, py + (i + 1) * LINE_SPACING, "│", f, TONES["BRIGHT"])
        gtext(d, px, py + (len(frame["prompt"]) + 1) * LINE_SPACING, "└" + "─" * inner + "┘", f,
              TONES["BRIGHT"])

    img.save(out)
    problems = []
    if box_bottom_y + LINE_SPACING > legend_y:
        problems.append(f"{scenario} {fb_w}x{fb_h}: the box bottom at y={box_bottom_y} runs into the "
                        f"control bar at y={legend_y}")
    if box_bottom_y > logical_h:
        problems.append(f"{scenario} {fb_w}x{fb_h}: the box is off-screen (logicalH={logical_h})")
    return problems


def main():
    args = [a for a in sys.argv[1:]]
    if not args:
        print(__doc__)
        return 1
    outdir = Path(args[0])
    outdir.mkdir(parents=True, exist_ok=True)
    windows = []
    i = 1
    while i < len(args):
        if args[i] == "--window" and i + 1 < len(args):
            w, h = args[i + 1].lower().split("x")
            windows.append((int(w), int(h)))
            i += 2
        else:
            i += 1
    if not windows:
        windows = [(1600, 900), (2560, 1080)]

    problems = []
    for fb_w, fb_h in windows:
        logical_w = widened(fb_w, fb_h)
        logical_h = max(1, logical_w * fb_h // fb_w)
        max_chars = max(40, logical_w // CHAR_WIDTH - 1)
        head_y, _, _, _ = header_bottom(logical_w)
        box_top = head_y + LINE_SPACING * 2
        capacity = max(RESERVED_ROWS, (logical_h - LINE_SPACING - LINE_SPACING - box_top) // LINE_SPACING - 1)
        frames, entry = dump(max_chars, capacity)
        tag = f"{fb_w}x{fb_h}"
        print(f"{tag}  logicalW={logical_w} logicalH={logical_h} cols={max_chars} boxRows={capacity}")
        for scenario, frame in frames.items():
            problems += render(scenario, frame, entry, fb_w, fb_h,
                               outdir / f"repair-{scenario}-{tag}.png")
            print(f"  {scenario:17} -> repair-{scenario}-{tag}.png")
        if fb_w == windows[0][0]:
            for world in ("absent", "present"):
                e = entry[world]
                print(f"  SETUP row [{world:7}] label='{e['label']}'  state='{e['state']}'")
                print(f"                     help='{e['help']}'")
    for p in problems:
        print("PROBLEM: " + p)
    return 3 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

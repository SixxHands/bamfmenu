"""Offline preview of the UPDATE AND REPAIR window: every realistic state, no Minecraft launch.
USAGE: python tools/repair_render.py <outdir> [--window WxH ...]
EXIT: 0 = rendered and no geometry problem, 1 = bad args, 2 = the Java dump failed, 3 = a row collided.
REQUIRES: pillow, fonttools (the font metrics come out of the shipped TTF, see metrics()).

Nothing about the layout is typed in here. The strings come from the SHIPPED classes (this javac-compiles
core/repair plus RepairEntry and asks RepairSample.session(scenario).frame(...) for each state); the canvas
size comes from the SHIPPED SetupSizing, the same widen rule both BIOS screens size off; the constants come
out of TerminalWindowProvider.java by regex; and the character pitch + line spacing come out of the bundled
font by replaying stb_truetype's arithmetic, which is what RichFont does at runtime.
IMPORTANT: the pitch used to be a hand-copied 14, taken from the provider's PRE-FONT placeholder field. The
baked gohufont measures 13, and the 1px error moved the widen rule's answer enough to hide a box that
collided with the control bar on every 2560x1080 window. Anything the game measures gets READ here, never
copied. What is still mirrored: the banner's linear header math (three lines, fed to SetupSizing as its
header function) and the box column offsets from RepairScreen.render.
IMPORTANT: the control bar is NOT in this canvas. drawControlBar sets up its own LOGICAL_W canvas so the bar
is one global element at one size, so on a widened body its row sits lower and its glyphs are bigger than
the box's; drawing it at logicalH - lineSpacing here put it somewhere the game never draws it and made a
5120x1440 collision invisible. SetupSizing.legendBodyY is asked where that row lands, same as the game's gate.
The gate at the end is the thing a screenshot cannot tell you: whether the box clears the bottom control
bar at that window shape.
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[1]   # tools/ lives at the repo root, so this runs from a clone
SRC = REPO / "earlywindow/src/main/java"
RES = REPO / "earlywindow/src/main/resources"
ART = RES / "ui"
PROVIDER = SRC / "com/retro/bamfmenu/early/bios/host/TerminalWindowProvider.java"
REPAIR_BOX = SRC / "com/retro/bamfmenu/early/core/repair/view/RepairBox.java"


def java_const(path, name, pattern, flags=0):
    m = re.search(pattern, path.read_text(encoding="utf-8"), flags)
    if not m:
        sys.exit(f"repair_render: could not read {name} out of {path.name}")
    return m.group(1)


# --- read out of the shipped code, so none of it can drift ---
LOGICAL_W = int(java_const(PROVIDER, "LOGICAL_W", r"int LOGICAL_W\s*=\s*(\d+)"))
MARGIN = int(java_const(PROVIDER, "MARGIN", r"int MARGIN\s*=\s*(\d+)"))
HEADER_FW = float(java_const(PROVIDER, "HEADER_FW", r"double HEADER_FW\s*=\s*([\d.]+)"))
LEFT_INNER = int(java_const(REPAIR_BOX, "LEFT_INNER", r"int LEFT_INNER\s*=\s*(\d+)"))
# Count SETUP_OPTIONS.length two ways so a parser mistake fails loudly instead of relaxing the layout check in render().
# (that one read 11 instead of 12). Either parse breaking now disagrees with the other and says so.
_OPTIONS = java_const(PROVIDER, "SETUP_OPTIONS", r"String\[\] SETUP_OPTIONS = \{(.*?)\n    \};", re.S)
_stripped = re.sub(r"//.*", "", re.sub(r"/\*.*?\*/", "", _OPTIONS, flags=re.S))
_by_comma = len([e for e in re.sub(r'"[^"]*"', "STR", _stripped).split(",") if e.strip()])
_by_line = len([l for l in _stripped.splitlines() if l.strip()])
if _by_comma != _by_line or _by_comma < 8:
    sys.exit(f"repair_render: SETUP_OPTIONS parses as {_by_comma} entries by comma but {_by_line} by line, "
             f"so the row budget is a guess. Fix the parse before trusting the collision gate.")
RESERVED_ROWS = _by_comma

_FACES = re.findall(r'new Face\("([^"]+)",\s*"([^"]+)",\s*(\d+),\s*(true|false)\)',
                    PROVIDER.read_text(encoding="utf-8"))
_ACTIVE = int(java_const(PROVIDER, "ACTIVE_FONT", r"Face ACTIVE_FONT\s*=\s*FONTS\[(\d+)\]"))
FACE_ID, FACE_RES, FACE_SIZE = _FACES[_ACTIVE][0], _FACES[_ACTIVE][1], int(_FACES[_ACTIVE][2])
FONT_FILE = RES / FACE_RES.lstrip("/")


def metrics():
    """RichFont's lineSpacing + the grid pitch, by replaying stb_truetype on the bundled TTF.
    stb: scale = size/(hhea.asc - hhea.desc); lineSpacing = (int)((asc - desc + gap) * scale); and every
    advance is (int)(hmtx.advanceWidth * scale). Cross-checked against a headless bake through the real
    LWJGL STB natives: gohufont@24 gives 28 and 13, and 398 of the 401 glyphs RichFont bakes advance 13
    (the exceptions are the two triangles U+25B6/U+25C0, which nothing in the UI or the art draws)."""
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        sys.exit("repair_render: needs fonttools (pip install fonttools) to read the shipped font's metrics")
    f = TTFont(str(FONT_FILE), fontNumber=0, lazy=True)
    hhea = f["hhea"]
    scale = FACE_SIZE / float(hhea.ascender - hhea.descender)
    line_spacing = int((hhea.ascender - hhea.descender + hhea.lineGap) * scale)
    name = f.getBestCmap().get(ord("0"))
    advance = int(f["hmtx"][name][0] * scale) if name else FACE_SIZE // 2
    f.close()
    return line_spacing, max(1, advance)


LINE_SPACING, CHAR_WIDTH = metrics()

BG = (10, 10, 12)
TONES = {
    "NORMAL": (0xAA, 0xAA, 0xAA),
    "BRIGHT": (0xFF, 0xFF, 0xFF),
    "DIM": (0x6E, 0x6E, 0x6E),
    "NOTICE": (0xFF, 0xD7, 0x00),
    "ALERT": (0xFF, 0x30, 0x30),
}

DUMPER = """
import com.retro.bamfmenu.early.bios.host.SetupSizing;
import com.retro.bamfmenu.early.bios.modes.repair.RepairEntry;
import com.retro.bamfmenu.early.core.repair.session.RepairSample;
import com.retro.bamfmenu.early.core.repair.session.RepairSession;
import com.retro.bamfmenu.early.core.repair.view.RepairFrame;

import java.util.function.IntUnaryOperator;

public class Dump {
    public static void main(String[] args) {
        int logicalW = Integer.parseInt(args[0]);
        int lineSpacing = Integer.parseInt(args[1]);
        int charWidth = Integer.parseInt(args[2]);
        int reserved = Integer.parseInt(args[3]);
        double bannerBottom = Double.parseDouble(args[4]);
        double bannerCanvas = Double.parseDouble(args[5]);
        // mirrors TerminalWindowProvider.headerBottomBodyY: the banner is width-anchored, so it is linear
        IntUnaryOperator header = w -> (int) (bannerBottom * w / bannerCanvas);
        for (int i = 6; i < args.length; i++) {
            String[] wh = args[i].split("x");
            int fbW = Integer.parseInt(wh[0]);
            int fbH = Integer.parseInt(wh[1]);
            int bodyW = SetupSizing.bodyWidth(logicalW, fbW, fbH, reserved, lineSpacing, header);
            int bodyH = SetupSizing.bodyHeight(bodyW, fbW, fbH);
            int maxChars = Math.max(40, bodyW / charWidth - 1);
            int boxTop = header.applyAsInt(bodyW) + 2 * lineSpacing;
            // the legend row comes from the shipped rule, because the bar draws in the logicalW canvas not this one
            int legendY = SetupSizing.legendBodyY(bodyW, logicalW, fbW, fbH, lineSpacing);
            int capacity = Math.max(reserved, (legendY - lineSpacing - boxTop) / lineSpacing - 1);
            System.out.println("W\\t" + args[i] + "\\t" + bodyW + "\\t" + bodyH + "\\t" + maxChars
                    + "\\t" + capacity + "\\t" + header.applyAsInt(bodyW) + "\\t" + legendY);
            for (String scenario : RepairSample.SCENARIOS) {
                RepairSession s = RepairSample.session(scenario);
                RepairFrame f = s.frame(maxChars, capacity, 0);
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


def gtext(d, x, y, text, font, fill, cw=None):
    """Draw on the CHARACTER GRID, one glyph per cell, the way RichFont's atlas does in game. PIL would
    otherwise use each glyph's own advance, and a face whose box-drawing glyphs are not the width of a digit
    slides the top bar's junction away from the interior divider it has to line up with."""
    cw = CHAR_WIDTH if cw is None else cw
    for i, ch in enumerate(text):
        if ch != " ":
            d.text((x + i * cw, y), ch, font=font, fill=fill)


def art(name, fallback):
    """Art.loadArt: the lines as shipped, blank lines trimmed off both ends."""
    p = ART / name
    if not p.is_file():
        return fallback
    lines = [ln.rstrip("\n") for ln in p.read_text(encoding="utf-8").splitlines()]
    while lines and not lines[-1].strip():
        lines.pop()
    while lines and not lines[0].strip():
        lines.pop(0)
    return lines or fallback


BAMF = art("bamfos.txt", ["B A M F O S"])
SIXX = art("sixxhands.txt", [])
# drawHeader's shared logo canvas, and the Y its bottom row sits at inside it
BANNER_CANVAS = max((len(l) for l in BAMF), default=1) * CHAR_WIDTH / HEADER_FW
BANNER_BOTTOM = LINE_SPACING * 2 + max(len(BAMF), len(SIXX)) * LINE_SPACING


def header_bottom(logical_w):
    """Mirror of TerminalWindowProvider.headerBottomBodyY, the same expression the dumper feeds SetupSizing."""
    return int(BANNER_BOTTOM * logical_w / BANNER_CANVAS)


def dump(windows):
    """Compile the shipped window once, then read every posed frame plus its canvas size out of it."""
    sources = sorted(str(p) for p in (SRC / "com/retro/bamfmenu/early/core/repair").rglob("*.java"))
    sources.append(str(SRC / "com/retro/bamfmenu/early/core/updater/OpsBridge.java"))
    sources.append(str(SRC / "com/retro/bamfmenu/early/bios/modes/repair/RepairEntry.java"))
    sources.append(str(SRC / "com/retro/bamfmenu/early/bios/host/SetupSizing.java"))
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
                              str(LOGICAL_W), str(LINE_SPACING), str(CHAR_WIDTH), str(RESERVED_ROWS),
                              repr(float(BANNER_BOTTOM)), repr(float(BANNER_CANVAS)),
                              *[f"{w}x{h}" for w, h in windows]],
                             capture_output=True, text=True, encoding="utf-8")
        if run.returncode != 0:
            print(run.stderr)
            sys.exit(2)
    if run.stderr.strip():
        print("  java stderr: " + run.stderr.strip().replace("\n", " | "))
    shots, entry, cur, tag = {}, {}, None, None
    for line in run.stdout.splitlines():
        f = line.split("\t")
        if f[0] == "W":
            tag = f[1]
            shots[tag] = {"logicalW": int(f[2]), "logicalH": int(f[3]), "maxChars": int(f[4]),
                          "capacity": int(f[5]), "headY": int(f[6]), "legendY": int(f[7]), "frames": {}}
        elif f[0] == "S":
            cur = f[1]
            shots[tag]["frames"][cur] = {"title": "", "top": "", "rows": [], "bottom": "",
                                         "hints": "", "prompt": []}
        elif f[0] == "T":
            shots[tag]["frames"][cur]["title"] = f[1]
        elif f[0] == "A":
            shots[tag]["frames"][cur]["top"] = f[1]
        elif f[0] == "R":
            shots[tag]["frames"][cur]["rows"].append({"leftTone": f[1], "left": f[2], "rightTone": f[3],
                                                      "right": f[4], "divider": f[5] == "true"})
        elif f[0] == "Z":
            shots[tag]["frames"][cur]["bottom"] = f[1]
        elif f[0] == "H":
            shots[tag]["frames"][cur]["hints"] = f[1]
        elif f[0] == "P":
            shots[tag]["frames"][cur]["prompt"].append(f[1] if len(f) > 1 else "")
        elif f[0] == "E":
            entry[f[1]] = {"label": f[2], "help": f[3], "state": f[4] if len(f) > 4 else ""}
    return shots, entry


def render(scenario, frame, geom, tag, out):
    logical_w, logical_h = geom["logicalW"], geom["logicalH"]
    max_chars, head_y = geom["maxChars"], geom["headY"]

    img = Image.new("RGB", (logical_w, logical_h), BG)
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(str(FONT_FILE), FACE_SIZE)

    # the banner renders in its own wider canvas, so scale it back into body pixels like the shader does
    scale = logical_w / BANNER_CANVAS
    bf = ImageFont.truetype(str(FONT_FILE), max(6, int(FACE_SIZE * scale)))
    for i, line in enumerate(BAMF):
        gtext(d, int(BANNER_CANVAS * 0.02 * scale), int((LINE_SPACING * 2 + i * LINE_SPACING) * scale),
              line, bf, TONES["NOTICE"], max(1, int(CHAR_WIDTH * scale)))
    for i, line in enumerate(SIXX):
        w = max((len(l) for l in SIXX), default=1) * CHAR_WIDTH
        gtext(d, int((BANNER_CANVAS - w - BANNER_CANVAS * 0.02) * scale),
              int((LINE_SPACING * 2 + i * LINE_SPACING) * scale), line, bf, TONES["NORMAL"],
              max(1, int(CHAR_WIDTH * scale)))

    gtext(d, MARGIN, head_y, "─" * max_chars, f, TONES["NORMAL"])
    title_y = head_y + LINE_SPACING
    gtext(d, MARGIN, title_y, frame["title"][:max_chars], f, TONES["BRIGHT"])

    box_top = title_y + LINE_SPACING
    legend_y = geom["legendY"]   # SetupSizing.legendBodyY, the bar's row mapped into THIS canvas
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

    # drawControlBar's own canvas: LOGICAL_W wide whatever the body is, centered in ITS columns, so in a
    # widened body the bar's glyphs come out proportionally bigger. Scaled here the same way the banner is.
    bar = logical_w / LOGICAL_W
    bar_font = ImageFont.truetype(str(FONT_FILE), max(6, int(FACE_SIZE * bar)))
    hints = frame["hints"][:max(16, LOGICAL_W // CHAR_WIDTH - 1)]
    bar_x = MARGIN + max(0, (LOGICAL_W // CHAR_WIDTH - len(hints)) // 2) * CHAR_WIDTH
    gtext(d, int(bar_x * bar), legend_y, hints, bar_font, TONES["DIM"], max(1, int(CHAR_WIDTH * bar)))

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
        problems.append(f"{scenario} {tag}: the box bottom at y={box_bottom_y} runs into the "
                        f"control bar at y={legend_y}")
    if box_bottom_y > logical_h:
        problems.append(f"{scenario} {tag}: the box is off-screen (logicalH={logical_h})")
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

    print(f"font {FACE_ID} @{FACE_SIZE}: lineSpacing={LINE_SPACING} pitch={CHAR_WIDTH}px  "
          f"(logicalW={LOGICAL_W} reserved={RESERVED_ROWS} rows, banner {len(BAMF)} rows)")
    shots, entry = dump(windows)
    problems = []
    for fb_w, fb_h in windows:
        tag = f"{fb_w}x{fb_h}"
        geom = shots[tag]
        print(f"{tag}  logicalW={geom['logicalW']} logicalH={geom['logicalH']} "
              f"cols={geom['maxChars']} boxRows={geom['capacity']} controlBarY={geom['legendY']}")
        for scenario, frame in geom["frames"].items():
            problems += render(scenario, frame, geom, tag, outdir / f"repair-{scenario}-{tag}.png")
            print(f"  {scenario:17} -> repair-{scenario}-{tag}.png")
    for world in ("absent", "present"):
        e = entry[world]
        print(f"  SETUP row [{world:7}] label='{e['label']}'  state='{e['state']}'")
        print(f"                     help='{e['help']}'")
    for p in problems:
        print("PROBLEM: " + p)
    return 3 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

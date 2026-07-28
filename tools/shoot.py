"""Photograph the REAL early window, one scenario per run, straight into assets/.
USAGE: python tools/shoot.py <outdir>
EXIT: 0 = every scenario captured, 2 = the build or a capture failed, 3 = a shot came out blank.
REQUIRES: pillow, a JDK, and gradle (to resolve the shots configuration once).

These are not renders. tools/shots/BootShot.java opens TerminalWindowProvider's actual GLFW window, poses a
scenario by writing the bus.log the engine would have written, drives the provider's own key callback, and
reads the GL front buffer. Whatever the provider draws is what lands in the PNG, including the parts nobody
remembered to mirror: the rule line, the dmesg row, the loading bar and the BIOS ID footer were all missing
from the offline version and nobody noticed until a real screenshot sat next to it.

The gamedir is a POSED throwaway, never a real instance: a real one would put the actual server address in
the repair window, and that already leaked into a committed screenshot once.

A blank capture is a HARD FAIL (exit 3). Every failure mode this harness has hit produced a perfectly
well-formed PNG of a single colour, so "it wrote a file" proves nothing.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[1]
HARNESS = REPO / "tools" / "shots" / "BootShot.java"

# Posed release the screenshots claim to be on. Only ever fake values live here, see the module docstring.
BCC = 'general{\n    modpackName = "BAMFOS"\n    modpackVersion = "v0.2.6"\n}\n'

IDENT = ["IDENT\tbamfos-pack", "CHANNEL\tok"]
SYNC = IDENT + ["SYNC\tbamfos-pack\tv0.2.4\tv0.2.6", "MANIFEST\t879"]

# Scenarios needing the BIOS to believe an updater is installed. Unbound, SETUP row 0 draws its
# no-updater label, which is a real state but not one a BAMFOS player is ever in.
BOUND = {"bios-setup"}

# name -> (bus lines, key presses). Keys go through the provider's real callback, in order.
VERBOSE = "post-downloading"  # dump this one scenario's harness output, the bar row is the thing to watch
SCENARIOS = {
    "post-quiet": (IDENT + ["END\tskip"], "-"),
    "post-downloading": (SYNC + [
        # Matches SyncCore's format ("PACK UPDATE <version>, <headline>").
        "NOTE\tPACK UPDATE v0.2.6, Nether rework lands: ash dunes, quartz shelves, and the new seam block.",
        "PLAN\t34",
        "FILE\tcobblemon-neoforge-1.6.1.jar",
        "FPROG\tcobblemon-neoforge-1.6.1.jar\t37958451\t50331648"], "-"),
    "post-applying": (SYNC + [
        "PLAN\t34", "FILE\tcreate-6.0.4.jar", "FDONE\tcreate-6.0.4.jar",
        "FILE\tsodium-0.6.13.jar", "FDONE\tsodium-0.6.13.jar", "PHASE\tApplying"], "-"),
    "post-synced": (SYNC + [
        "PLAN\t34", "FILE\tcreate-6.0.4.jar", "FDONE\tcreate-6.0.4.jar",
        "PHASE\tApplying", "SYNCED\tv0.2.4\tv0.2.6", "END\tok"], "-"),
    "post-pending": (SYNC + [
        "PLAN\t3", "FILE\tbamfmenu-earlywindow-1.0.1.jar", "FDONE\tbamfmenu-earlywindow-1.0.1.jar",
        "PHASE\tApplying", "SYNCED\tv0.2.4\tv0.2.6", "PENDING", "END\tok"], "-"),
    "post-ratelimited": (SYNC + ["END\tlimited"], "-"),
    "post-offline": (IDENT + ["SYNC\tbamfos-pack\tv0.2.4\tv0.2.6", "END\toffline"], "-"),
    "post-failed": (SYNC + ["PLAN\t34", "FILE\tcobblemon-neoforge-1.6.1.jar", "END\tfail"], "-"),
    # DEL opens SETUP. Don't shoot the repair window here (it needs live updater ops; repair_render.py renders that window).
    "bios-setup": (IDENT + ["END\tskip"], "DEL"),
}


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    return r.returncode, r.stdout, r.stderr


def classpath():
    """Resolve once from gradle. The shots configuration pins the same LWJGL + FancyModLoader versions
    NeoForge provides at runtime, so this cannot drift from what the provider is compiled against."""
    gradlew = REPO / "gradlew.bat"
    rc, out, err = run([str(gradlew), ":earlywindow:shotsClasspath", "-q", "--console=plain"], cwd=REPO)
    for line in out.splitlines():
        if "lwjgl" in line and ";" in line:
            return line.strip()
    print(err or out)
    sys.exit(2)


def main():
    pos = [a for a in sys.argv[1:] if not a.startswith("-")]
    if len(pos) != 1:
        print(__doc__)
        return 1
    out = Path(pos[0])
    out.mkdir(parents=True, exist_ok=True)

    cp = classpath()
    build = Path(tempfile.mkdtemp(prefix="bootshot"))
    rc, so, se = run(["javac", "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", str(build), str(HARNESS)])
    if rc != 0:
        print(se)
        return 2

    blank = []
    items = [(VERBOSE, SCENARIOS[VERBOSE])] if "--one" in sys.argv else list(SCENARIOS.items())
    for name, (bus, keys) in items:
        game = Path(tempfile.mkdtemp(prefix="shotgame"))
        (game / "config").mkdir(parents=True, exist_ok=True)
        (game / "config" / "bcc-common.toml").write_text(BCC, encoding="utf-8")
        busfile = game / "bus.txt"
        # write_bytes, NOT write_text: Windows \r\n leaves a trailing \r that breaks numeric fields (MANIFEST, FPROG) in UpdaterFeed.
        busfile.write_bytes(("\n".join(bus) + "\n").encode("utf-8"))

        rc, so, se = run(["java", f"-Dbootshot.gamedir={game}", "-cp", str(build) + ";" + cp, "BootShot",
                          str(out), str(game), name, str(busfile), keys,
                          "bound" if name in BOUND else "-"])
        if rc != 0:
            print(f"  {name}: FAILED\n{se[:800]}")
            return 2
        if "-v" in sys.argv or name == VERBOSE:
            for line in so.splitlines():
                print("    " + line)

        png = out / f"{name}.png"
        colours = len(set(Image.open(png).convert("RGB").getdata()))
        # every failure this harness has had wrote a valid single-colour PNG, so count colours, not files
        flag = "  BLANK" if colours < 5 else ""
        print(f"  {name:<20} {colours:>4} colours{flag}")
        if colours < 5:
            blank.append(name)
        shutil.rmtree(game, ignore_errors=True)

    shutil.rmtree(build, ignore_errors=True)
    if blank:
        print("BLANK captures, these are not usable: " + ", ".join(blank))
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())

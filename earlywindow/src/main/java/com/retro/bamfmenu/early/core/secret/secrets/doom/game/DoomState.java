package com.retro.bamfmenu.early.core.secret.secrets.doom.game;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* Reads DOOM's live HUD + menu state out of the running engine by REFLECTION (the player + menu structures live in
   the vendored engine's packages and aren't on our compile classpath as types). DOOM's own HUD/menu are tiny bitmap
   fonts that turn to unreadable mush when downsampled to ASCII, so instead we read the real numbers/labels and let
   DoomScreen draw them as crisp terminal text. Fully defensive: any reflection failure yields a blank snapshot. */
public final class DoomState {

    /** Live HUD + menu snapshot. menuItems are already display-ready (lump names mapped to text).
        message != null  => DOOM is showing a StartMessage prompt (quit/end-game/nightmare confirm, etc.); render
                            THAT, not the (stale) underlying menu, since the engine leaves currentMenu unchanged.
        helpScreen       => a READ THIS!/help page (a full-screen image we can't show as text) is up. */
    public record Snapshot(boolean inLevel, int health, int armor, int ammo, boolean hasAmmo, String weapon,
                           boolean menuActive, String[] menuItems, int menuSel, String message, boolean helpScreen,
                           boolean[] arms, int[] ammoCounts, int[] ammoMax, boolean[] keys) {
        public static final Snapshot EMPTY =
                new Snapshot(false, 0, 0, 0, false, "", false, null, -1, null, false, null, null, null, null);
    }

    private static final String[] WEAPONS =
            {"FIST", "PISTOL", "SHOTGUN", "CHAINGUN", "ROCKET", "PLASMA", "BFG9000", "CHAINSAW", "SUPER SHOTGUN"};
    /** ammotype index used by each weapon ordinal (am_clip=0, am_shell=1, am_cell=2, am_misl=3; -1 = no ammo). */
    private static final int[] WEAPON_AMMO = {-1, 0, 1, 0, 3, 2, 2, -1, 1};

    /** DOOM menu items store the GRAPHIC LUMP name, not display text; map the ones used to readable labels. */
    private static final Map<String, String> LUMP_TEXT = Map.ofEntries(
            Map.entry("M_NGAME", "NEW GAME"), Map.entry("M_OPTION", "OPTIONS"),
            Map.entry("M_LOADG", "LOAD GAME"), Map.entry("M_SAVEG", "SAVE GAME"),
            Map.entry("M_RDTHIS", "READ THIS!"), Map.entry("M_QUITG", "QUIT GAME"),
            Map.entry("M_EPI1", "KNEE-DEEP IN THE DEAD"), Map.entry("M_EPI2", "THE SHORES OF HELL"),
            Map.entry("M_EPI3", "INFERNO"), Map.entry("M_EPI4", "THY FLESH CONSUMED"),
            Map.entry("M_JKILL", "I'M TOO YOUNG TO DIE"), Map.entry("M_ROUGH", "HEY, NOT TOO ROUGH"),
            Map.entry("M_HURT", "HURT ME PLENTY"), Map.entry("M_ULTRA", "ULTRA-VIOLENCE"),
            Map.entry("M_NMARE", "NIGHTMARE!"), Map.entry("M_ENDGAM", "END GAME"),
            Map.entry("M_MESSG", "MESSAGES"), Map.entry("M_DETAIL", "GRAPHIC DETAIL"),
            Map.entry("M_SCRNSZ", "SCREEN SIZE"), Map.entry("M_MSENS", "MOUSE SENSITIVITY"),
            Map.entry("M_SVOL", "SOUND VOLUME"), Map.entry("M_SFXVOL", "SFX VOLUME"),
            Map.entry("M_MUSVOL", "MUSIC VOLUME"));

    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static boolean warnedMenu, warnedHud; // rate-limit the degrade log (read() is single-threaded: render loop)

    public static Snapshot read(Object dm) {
        if (dm == null) {
            return Snapshot.EMPTY;
        }
        // Guard the menu read and the HUD read SEPARATELY: one reflection failure (e.g. future engine field drift)
        // must degrade only its own overlay, not silently wipe the whole thing, and it logs once so it's debuggable.
        boolean menuActive = false;
        String[] items = null;
        int sel = -1;
        String message = null;
        boolean helpScreen = false;
        try {
            menuActive = Boolean.TRUE.equals(get(dm, "menuactive"));
            if (menuActive) {
                Object menu = get(dm, "menu");
                if (menu != null) {
                    // A StartMessage prompt (quit/end-game/nightmare confirm...) sets menuactive=true but leaves
                    // currentMenu pointing at the old menu; read the prompt text so we don't draw a stale list.
                    if (Boolean.TRUE.equals(get(menu, "messageToPrint"))) {
                        Object ms = get(menu, "messageString");
                        String m = ms == null ? "" : ms.toString();
                        message = m.isEmpty() ? null : m; // keep the invariant: message != null IFF a prompt is showing
                    }
                    Object cur = get(menu, "currentMenu");
                    if (cur != null && get(cur, "menuitems") instanceof Object[] mi) {
                        int num = Math.min(asInt(get(cur, "numitems")), mi.length);
                        // DOOM's READ THIS! pages are a 1-item menu whose only item has a blank lump name (the page is
                        // a full-screen graphic, not a list); flag it so we don't render a phantom "[ EMPTY ]" row.
                        helpScreen = num == 1 && isBlankNameItem(mi[0]);
                        List<String> list = new ArrayList<>();
                        for (int i = 0; i < num; i++) {
                            list.add(menuLabel(menu, mi[i], i));
                        }
                        items = list.toArray(new String[0]);
                        sel = asInt(get(menu, "itemOn"));
                    }
                }
            }
        } catch (Throwable t) {
            warnOnce("menu", t);
        }

        boolean inLevel = false;
        int health = 0, armor = 0, ammo = 0;
        boolean hasAmmo = false;
        String weapon = "";
        boolean[] arms = null, keys = null;
        int[] ammoCounts = null, ammoMax = null;
        try {
            Object gs = get(dm, "gamestate");
            inLevel = (gs instanceof Enum<?> ge) && "GS_LEVEL".equals(ge.name());
            if (inLevel && get(dm, "players") instanceof Object[] parr) {
                int cp = asInt(get(dm, "consoleplayer"));
                if (cp >= 0 && cp < parr.length && parr[cp] != null) {
                    Object p = parr[cp];
                    Object mo = get(p, "mo");
                    Object moHp = mo == null ? null : get(mo, "health");
                    health = (moHp instanceof Number n) ? n.intValue() : firstInt(get(p, "health"));
                    armor = firstInt(get(p, "armorpoints"));
                    arms = (get(p, "weaponowned") instanceof boolean[] wo) ? wo : null;          // ARMS grid
                    ammoCounts = (get(p, "ammo") instanceof int[] aa) ? aa : null;               // BULL/SHEL/RCKT/CELL
                    ammoMax = (get(p, "maxammo") instanceof int[] mm) ? mm : null;
                    keys = (get(p, "cards") instanceof boolean[] cc) ? cc : null;                // blue/yellow/red keys
                    int wi = (get(p, "readyweapon") instanceof Enum<?> e) ? e.ordinal() : -1;
                    if (wi >= 0 && wi < WEAPONS.length) {
                        weapon = WEAPONS[wi];
                        int ai = WEAPON_AMMO[wi];
                        if (ai >= 0 && ammoCounts != null && ai < ammoCounts.length) {
                            ammo = ammoCounts[ai];
                            hasAmmo = true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            warnOnce("hud", t);
        }
        return new Snapshot(inLevel, health, armor, ammo, hasAmmo, weapon, menuActive, items, sel, message, helpScreen,
                arms, ammoCounts, ammoMax, keys);
    }

    private static void warnOnce(String tag, Throwable t) {
        if ("menu".equals(tag) && !warnedMenu) {
            warnedMenu = true;
            System.err.println("[bamfmenu] DoomState menu read failed (menu overlay degraded): " + t);
        } else if ("hud".equals(tag) && !warnedHud) {
            warnedHud = true;
            System.err.println("[bamfmenu] DoomState HUD read failed (HUD overlay degraded): " + t);
        }
    }

    private static boolean isBlankNameItem(Object item) throws IllegalAccessException {
        if (item == null) {
            return true;
        }
        if (asInt(get(item, "status")) == -1) {
            return false; // a separator row, not a help page
        }
        Object nm = get(item, "name");
        return nm == null || nm.toString().isEmpty();
    }

    private static String menuLabel(Object menu, Object item, int index) throws IllegalAccessException {
        if (item == null) {
            return "";
        }
        int status = asInt(get(item, "status"));
        if (status == -1) {
            return ""; // separator row
        }
        Object nmO = get(item, "name");
        String nm = nmO == null ? "" : nmO.toString();
        if (!nm.isEmpty()) {
            return LUMP_TEXT.getOrDefault(nm, nm);
        }
        // empty name + selectable = a Load/Save slot -> show the saved name if any. The engine stores these as a
        // char[][] (NUL-terminated, padded to SAVESTRINGSIZE), NOT String[]; decode up to the first 0x00.
        if (get(menu, "savegamestrings") instanceof char[][] sg && index < sg.length && sg[index] != null) {
            String s = nulTerminated(sg[index]);
            if (!s.isBlank()) {
                return s;
            }
        }
        return "[ EMPTY ]";
    }

    private static String nulTerminated(char[] c) {
        int n = 0;
        while (n < c.length && c[n] != 0) {
            n++;
        }
        return new String(c, 0, n);
    }

    private static Object get(Object o, String name) throws IllegalAccessException {
        Field f = field(o.getClass(), name);
        return f == null ? null : f.get(o);
    }

    private static Field field(Class<?> c, String name) {
        String key = c.getName() + "#" + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        return null;
    }

    private static int asInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : 0;
    }

    private static int firstInt(Object arr) {
        return (arr instanceof int[] a && a.length > 0) ? a[0] : 0;
    }

    private DoomState() {
    }
}

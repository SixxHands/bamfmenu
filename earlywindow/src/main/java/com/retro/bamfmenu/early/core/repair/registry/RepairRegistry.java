package com.retro.bamfmenu.early.core.repair.registry;

import com.retro.bamfmenu.early.core.repair.api.RepairDef;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.util.ResourceScan;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Auto-discovers RepairDef classes exactly one level under `repair/items/` (ignoring subfolders).
 * A def that throws is logged and skipped so one broken row never takes down the rest of the menu.
 */
public final class RepairRegistry {

    /* Base package (slash-separated for scanning) containing per-item subpackages with RepairDef classes.
       Keep subpackage names lowercase a-z0-9, since the pattern below ignores anything else. */
    private static final String ITEMS_DIR = "com/retro/bamfmenu/early/core/repair/items";
    private static final String DEF_PATTERN = "[a-z0-9]+/\\w+\\.class";

    private final List<RepairItem> items;

    /** Scans the items folder. Built on the page's FIRST open, because a boot that never opens the window
     *  should not pay for a jar walk. */
    public RepairRegistry() {
        this(discover());
    }

    /** Explicit item list for tests or offline renders that pass a custom menu. */
    public RepairRegistry(List<RepairItem> items) {
        this.items = List.copyOf(items);
    }

    /** Every discovered row, in menu order. */
    public List<RepairItem> items() {
        return items;
    }

    private static List<RepairItem> discover() {
        record Found(int order, String id, RepairItem item) {
        }
        List<Found> found = new ArrayList<>();
        ClassLoader cl = RepairRegistry.class.getClassLoader();
        for (String path : ResourceScan.listResourcesDeep(ITEMS_DIR, DEF_PATTERN)) {
            // path is "/com/retro/.../items/<name>/Foo.class" -> the fully-qualified class name
            String fqn = path.substring(1, path.length() - ".class".length()).replace('/', '.');
            try {
                Class<?> cls = Class.forName(fqn, false, cl);
                if (!RepairDef.class.isAssignableFrom(cls) || cls.isInterface()
                        || Modifier.isAbstract(cls.getModifiers())) {
                    continue;
                }
                RepairDef def = (RepairDef) cls.getDeclaredConstructor().newInstance();
                RepairItem item = def.item();
                if (item != null && item.gate() != null && item.effect() != null) {
                    found.add(new Found(def.order(), item.id(), item));
                }
            } catch (Throwable t) {
                System.err.println("[bamfmenu] repair item skipped (" + fqn + "): " + t);
            }
        }
        // order() first, then id, so two rows sharing an order still land in the same place every boot
        found.sort(Comparator.comparingInt(Found::order).thenComparing(Found::id));
        List<RepairItem> out = new ArrayList<>(found.size());
        for (Found f : found) {
            out.add(f.item());
        }
        System.err.println("[bamfmenu] repair items: " + out.size() + " loaded");
        return out;
    }
}

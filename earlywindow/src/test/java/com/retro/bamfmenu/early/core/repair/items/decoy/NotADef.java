package com.retro.bamfmenu.early.core.repair.items.decoy;

/**
 * Deliberate non-RepairDef decoy at `items/<lowercase>/<Class>.class` on the test classpath.
 * Keeps ShadowedClasspathTest live so ResourceScan must walk every classpath root instead of
 * stopping at the test root and returning zero repair items. Do not delete.
 */
public final class NotADef {

    private NotADef() {
    }
}

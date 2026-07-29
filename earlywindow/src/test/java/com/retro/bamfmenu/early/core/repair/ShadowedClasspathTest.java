package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.registry.RepairRegistry;
import com.retro.bamfmenu.early.core.util.ResourceScan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ResourceScan walks every classpath root rather than stopping at the first.
 * Uses `items/decoy/NotADef.class` on the test root to exercise a two-root classpath without mocks.
 */
class ShadowedClasspathTest {

    private static final String ITEMS = "com/retro/bamfmenu/early/core/repair/items";

    @Test
    void theScanSeesBothRootsAndNotJustWhicheverAnswersFirst() {
        List<String> found = ResourceScan.listResourcesDeep(ITEMS, "[a-z0-9]+/\\w+\\.class");

        assertTrue(found.stream().anyMatch(p -> p.endsWith("/decoy/NotADef.class")), String.valueOf(found));
        assertTrue(found.stream().anyMatch(p -> p.endsWith("/verify/VerifyItem.class")), String.valueOf(found));
    }

    /** Registry must skip non-RepairDef decoys on the classpath and load every real item. */
    @Test
    void aDecoyOnTheClasspathCannotEmptyTheMenu() {
        List<String> ids = new java.util.ArrayList<>();
        new RepairRegistry().items().forEach(i -> ids.add(i.id()));

        assertFalse(ids.isEmpty());
        assertTrue(ids.contains("verify") && ids.contains("factory"), String.valueOf(ids));
        assertFalse(ids.contains("decoy"), String.valueOf(ids));
    }
}

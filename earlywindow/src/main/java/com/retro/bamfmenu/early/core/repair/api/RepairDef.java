package com.retro.bamfmenu.early.core.repair.api;

/* One menu row, placed at the root of a subfolder under repair/items/ (needs a public no-arg ctor).
   RepairRegistry scans one level down automatically; put pages and helpers in deeper subfolders to hide them. */
public interface RepairDef {

    RepairItem item();

    /* A MENU needs a stable order and jar scan order is alphabetical, so each def states its place.
       The shipped rows step by ten, leaving room to slot one in without a renumber. */
    default int order() {
        return 100;
    }
}

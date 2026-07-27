package com.retro.bamfmenu.early.core.repair.ops;

import com.retro.bamfmenu.early.core.repair.api.ReleaseList;
import com.retro.bamfmenu.early.core.repair.api.RepairOps;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.updater.OpsBridge;

import java.util.Map;

/**
 * Thin reflective wrapper over OpsBridge (blocking calls; run on the worker).
 * Kept as one-line forwards so the engine stays the single writer and BIOS code never touches manifests.
 */
public final class EngineOps implements RepairOps {

    @Override
    public UpdaterState read(long nowMillis) {
        return UpdaterState.of(OpsBridge.map("status"), OpsBridge.rows("listOptional"),
                OpsBridge.text("releaseNotes"), nowMillis);
    }

    @Override
    public ReleaseList releases() {
        Map<String, Object> answer = OpsBridge.map("listReleases");
        return ReleaseList.of(answer, OpsBridge.rowsIn(answer, "releases"));
    }

    @Override
    public int repair() {
        return OpsBridge.code("repair");
    }

    @Override
    public int syncNow() {
        return OpsBridge.code("syncNow");
    }

    @Override
    public int syncTo(String tag) {
        return OpsBridge.code("syncTo", tag);
    }

    @Override
    public int unpin() {
        return OpsBridge.code("unpin");
    }

    @Override
    public boolean setDisabled(String path, boolean disabled) {
        return OpsBridge.flag("setDisabled", path, disabled);
    }

    @Override
    public int setServer(String host, int port) {
        return OpsBridge.code("setServer", host, port);
    }
}

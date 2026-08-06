package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.items.server.ServerItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the recovery server address field and what it prefills on open. */
class ServerItemTest {

    @Test
    void theFieldOpensEmptyOnABoxThatNeverSetAnAddress() {
        assertEquals("", edit().initial().apply(posed("", 0)));
        assertEquals("from your server list", new ServerItem().item().status().line(posed("", 0)));
    }

    @Test
    void theFieldOpensOnTheAddressTheBoxActuallyHolds() {
        assertEquals("play.example.net:25599", edit().initial().apply(posed("play.example.net", 25599)));
        assertEquals("play.example.net", edit().initial().apply(posed("play.example.net", 25565)));
    }

    /** ENTER on an untouched field must re-save the existing address; prefill and apply() share one format. */
    @Test
    void enterOnAnUntouchedFieldSavesBackTheSameAddress() {
        RepairSessionTest.FakeOps ops = new RepairSessionTest.FakeOps();
        String prefill = edit().initial().apply(posed("play.example.net", 25599));
        RepairResult r = edit().action().run(ops, prefill);
        assertEquals("play.example.net", ops.serverHost);
        assertEquals(25599, ops.serverPort);
        assertEquals(RepairResult.Kind.DONE, r.kind(), r.message());
    }

    private static RepairEffect.Edit edit() {
        RepairItem item = new ServerItem().item();
        assertTrue(item.effect() instanceof RepairEffect.Edit);
        return (RepairEffect.Edit) item.effect();
    }

    /* Uses the actual engine status keys since the bug was in how they were parsed. */
    private static UpdaterState posed(String host, int port) {
        return UpdaterState.of(Map.<String, Object>of("server_host", host, "server_port", (long) port),
                List.of(), "", 0L);
    }
}

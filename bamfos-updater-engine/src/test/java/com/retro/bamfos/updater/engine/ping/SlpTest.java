package com.retro.bamfos.updater.engine.ping;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Tests ping timeout budgets. Full socket/wire behavior is covered in ClientDoorTest. */
class SlpTest {

    /**
     * DNS resolution must stay inside the 2500 ms budget instead of blocking eagerly in InetSocketAddress
     * when DNS is stalled (NXDOMAIN returns too fast to catch this).
     */
    @Test
    void aSlowResolverCannotOutlastTheBudget() {
        long deadline = System.nanoTime() + 300L * 1_000_000L;
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> Slp.resolveWithin("bamfos.invalid", deadline, host -> {
            try {
                Thread.sleep(30_000); // slow DNS resolver that responds well past the timeout
            } catch (InterruptedException abandoned) {
                Thread.currentThread().interrupt();
            }
            return InetAddress.getLoopbackAddress();
        }));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 3_000, String.valueOf(ms));
        assertTrue(e.getMessage().contains("budget exhausted"), e.getMessage());
    }

    /** Verifies status() calls resolveWithin rather than eager InetSocketAddress (127.0.0.1 tests miss this). */
    @Test
    void statusResolvesThroughTheBudgetedLookup_notEagerly() {
        AtomicBoolean used = new AtomicBoolean();
        long t0 = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> Slp.status("bamfos.invalid", 25565, host -> {
            used.set(true);
            try {
                Thread.sleep(30_000); // simulates broken DNS where getaddrinfo hangs
            } catch (InterruptedException abandoned) {
                Thread.currentThread().interrupt();
            }
            return InetAddress.getLoopbackAddress();
        }));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(used.get());
        assertTrue(ms < 4_000, String.valueOf(ms));
        assertTrue(e.getMessage().contains("budget exhausted"), e.getMessage());
    }

    /**
     * Re-arm SO_TIMEOUT from the shared deadline on each read so a peer trickling bytes every 700 ms still
     * times out at the 2500 ms total budget instead of resetting the timeout per read.
     */
    @Test
    void aDribblingServerCannotOutlastTheBudget() throws IOException {
        try (ServerSocket srv = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = srv.accept()) {
                    OutputStream out = s.getOutputStream();
                    for (int i = 0; i < 3; i++) {
                        Thread.sleep(700);
                        out.write(0x80); // a continuation byte, so the varint is never finished
                        out.flush();
                    }
                    Thread.sleep(60_000); // then EOF immediately
                } catch (Exception over) {
                    // the test closing the socket lands here
                }
            });
            t.setDaemon(true);
            t.start();

            long t0 = System.nanoTime();
            assertThrows(IOException.class, () -> Slp.status("127.0.0.1", srv.getLocalPort()));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 3_600, String.valueOf(ms));
        }
    }

    @Test
    void aResolverThatAnswersIsJustPassedThrough() throws IOException {
        long deadline = System.nanoTime() + 2_500L * 1_000_000L;
        assertEquals(InetAddress.getLoopbackAddress(),
                Slp.resolveWithin("localhost", deadline, host -> InetAddress.getLoopbackAddress()));
    }
}

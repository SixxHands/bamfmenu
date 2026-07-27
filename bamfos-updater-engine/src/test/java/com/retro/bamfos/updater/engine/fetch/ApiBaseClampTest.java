package com.retro.bamfos.updater.engine.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the BAMFOS_API_BASE clamp so an env var cannot redirect hop-0 requests (which carry the
 * embedded PAT) to an arbitrary server. */
class ApiBaseClampTest {

    /** Test overrides only need loopback addresses, so reject everything else. */
    @Test
    void aLocalFixtureIsStillHonored() {
        assertTrue(GithubReleases.loopback("http://127.0.0.1:8080"));
        assertTrue(GithubReleases.loopback("http://localhost:45799/api"));
        assertTrue(GithubReleases.loopback("https://127.0.0.1"));
        assertTrue(GithubReleases.loopback("  http://127.0.0.1:1234  "));
        assertTrue(GithubReleases.loopback("http://[::1]:8080"));
    }

    /** Reject URI userinfo spoofing (which fools startsWith checks and sends the bearer token to
     * evil.example); the clamp must check the parsed host. */
    @Test
    void aHostWearing127AsUserinfo_isNotTheLoopback() {
        assertFalse(GithubReleases.loopback("http://127.0.0.1@evil.example/"));
        assertFalse(GithubReleases.loopback("https://localhost.evil.example/repos"));
        assertFalse(GithubReleases.loopback("http://127.0.0.1.evil.example/"));
    }

    @Test
    void anythingElse_leavesTheShippedRunPointedAtGithub() {
        assertFalse(GithubReleases.loopback(null));
        assertFalse(GithubReleases.loopback(""));
        assertFalse(GithubReleases.loopback("   "));
        assertFalse(GithubReleases.loopback("http://10.0.0.5:8080"));
        assertFalse(GithubReleases.loopback("https://api.github.com"));
        assertFalse(GithubReleases.loopback("not a uri at all >>"));
    }

    /** Regardless of environment variables, a shipped run may only resolve to GitHub or loopback. */
    @Test
    void realApi_canOnlyEverAnswerGithubOrTheLoopback() {
        String resolved = GithubReleases.realApi();
        assertTrue(GithubReleases.API.equals(resolved) || GithubReleases.loopback(resolved), String.valueOf(resolved));
        assertEquals("https://api.github.com", GithubReleases.API);
    }
}

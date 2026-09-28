package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account deletion is irreversible and `requestAccountDeletion` only accepts it while `auth_time`
 * is recent. `auth_time` is the original sign-in time — a forced token refresh does not move it —
 * so these tests pin the client's decision to route the user through a fresh sign-in rather than
 * spending a request the server is guaranteed to refuse. The server stays authoritative: the
 * callable still enforces the window, and its `failed-precondition` maps to the same handling.
 */
class RecentAuthTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a recent sign-in may go straight to the callable`() {
        assertFalse(deletionSessionIsStale(now - 30_000, now))
        // The callable refuses only when the session is strictly older than its window.
        assertFalse(deletionSessionIsStale(now - RECENT_AUTH_WINDOW_MILLIS, now))
    }

    @Test
    fun `a session older than the window needs a fresh sign-in`() {
        assertTrue(deletionSessionIsStale(now - RECENT_AUTH_WINDOW_MILLIS - 1, now))
        assertTrue(deletionSessionIsStale(now - 60 * 60 * 1000, now))
    }

    @Test
    fun `an unknown sign-in record is treated as stale`() {
        assertTrue(deletionSessionIsStale(null, now))
        assertTrue(deletionSessionIsStale(0L, now))
    }

    @Test
    fun `the client window mirrors the callable window`() {
        assertEquals(5 * 60 * 1000L, RECENT_AUTH_WINDOW_MILLIS)
    }

    @Test
    fun `a stale-session refusal has its own failure kind`() {
        assertEquals(
            AuthFailureKind.ReauthRequired,
            authFailureKindFor(localError = RecentAuthRequiredException()),
        )
        // This is how `requestAccountDeletion` reports the gate.
        assertEquals(
            AuthFailureKind.ReauthRequired,
            authFailureKindFor(functionsCodeName = "FAILED_PRECONDITION"),
        )
    }

    @Test
    fun `other failures keep their existing kinds`() {
        assertEquals(AuthFailureKind.Network, authFailureKindFor(functionsCodeName = "UNAVAILABLE"))
        assertEquals(AuthFailureKind.NotFound, authFailureKindFor(functionsCodeName = "NOT_FOUND"))
        assertEquals(AuthFailureKind.RateLimited, authFailureKindFor(functionsCodeName = "RESOURCE_EXHAUSTED"))
        assertEquals(AuthFailureKind.Network, authFailureKindFor(authCode = "ERROR_NETWORK_REQUEST_FAILED"))
        assertEquals(AuthFailureKind.Validation, authFailureKindFor(localError = IllegalArgumentException("bad")))
        assertEquals(AuthFailureKind.Unknown, authFailureKindFor())
    }
}

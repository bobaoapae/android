package io.homeassistant.companion.android.common.data.websocket

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsLiveConnectionTrustworthyTest {

    @Test
    fun `Given WebSocket Active and active network when checking then returns true`() {
        assertTrue(isLiveConnectionTrustworthy(webSocketState = WebSocketState.Active, hasActiveNetwork = true))
    }

    @Test
    fun `Given WebSocket Active but no active network when checking then returns false`() {
        // OkHttp lags up to ~45s before transitioning to Closed after a dropped link, so the
        // WebSocket can still report Active while ConnectivityManager already reports no
        // transport. The latter must win to avoid presenting stale state as live.
        assertFalse(isLiveConnectionTrustworthy(webSocketState = WebSocketState.Active, hasActiveNetwork = false))
    }

    @Test
    fun `Given WebSocket Closed when checking then returns false regardless of network`() {
        assertFalse(
            isLiveConnectionTrustworthy(
                webSocketState = WebSocketState.ClosedOther,
                hasActiveNetwork = true,
            ),
        )
        assertFalse(
            isLiveConnectionTrustworthy(
                webSocketState = WebSocketState.ClosedOther,
                hasActiveNetwork = false,
            ),
        )
    }

    @Test
    fun `Given WebSocket Authenticating when checking then returns false`() {
        // The TLS and auth handshake is still in progress, no entity events flowing yet.
        assertFalse(
            isLiveConnectionTrustworthy(
                webSocketState = WebSocketState.Authenticating,
                hasActiveNetwork = true,
            ),
        )
    }

    @Test
    fun `Given WebSocket Initial when checking then returns false`() {
        assertFalse(
            isLiveConnectionTrustworthy(
                webSocketState = WebSocketState.Initial,
                hasActiveNetwork = true,
            ),
        )
    }

    @Test
    fun `Given null WebSocket state when checking then returns false`() {
        // Cold start before any WebSocketRepository has been resolved into the cache.
        assertFalse(isLiveConnectionTrustworthy(webSocketState = null, hasActiveNetwork = true))
    }

    @Test
    fun `Given WebSocket ClosedAuth when checking then returns false`() {
        assertFalse(
            isLiveConnectionTrustworthy(
                webSocketState = WebSocketState.ClosedAuth,
                hasActiveNetwork = true,
            ),
        )
    }
}

package io.homeassistant.companion.android.controls

import io.homeassistant.companion.android.common.data.websocket.WebSocketState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsEntityStateTrustworthyTest {

    @Test
    fun `Given WebSocket Active and active network when checking then returns true`() {
        assertTrue(isEntityStateTrustworthy(webSocketState = WebSocketState.Active, hasActiveNetwork = true))
    }

    @Test
    fun `Given WebSocket Active but no active network when checking then returns false`() {
        // Bug 2 scenario: OkHttp hasn't detected the dropped link yet (ping/read timeout
        // takes up to ~45s), so the WebSocket state is still Active. Android's
        // ConnectivityManager already reports no transport, which must win: rendering
        // the cached state as live would mislead the user.
        assertFalse(isEntityStateTrustworthy(webSocketState = WebSocketState.Active, hasActiveNetwork = false))
    }

    @Test
    fun `Given WebSocket Closed when checking then returns false regardless of network`() {
        assertFalse(
            isEntityStateTrustworthy(
                webSocketState = WebSocketState.ClosedOther,
                hasActiveNetwork = true,
            ),
        )
        assertFalse(
            isEntityStateTrustworthy(
                webSocketState = WebSocketState.ClosedOther,
                hasActiveNetwork = false,
            ),
        )
    }

    @Test
    fun `Given WebSocket Authenticating when checking then returns false`() {
        // During the TLS + auth handshake we are not yet receiving entity events.
        assertFalse(
            isEntityStateTrustworthy(
                webSocketState = WebSocketState.Authenticating,
                hasActiveNetwork = true,
            ),
        )
    }

    @Test
    fun `Given WebSocket Initial when checking then returns false`() {
        assertFalse(
            isEntityStateTrustworthy(
                webSocketState = WebSocketState.Initial,
                hasActiveNetwork = true,
            ),
        )
    }

    @Test
    fun `Given null WebSocket state when checking then returns false`() {
        // Cold start before any WebSocketRepository has been resolved into the cache.
        assertFalse(isEntityStateTrustworthy(webSocketState = null, hasActiveNetwork = true))
    }

    @Test
    fun `Given WebSocket ClosedAuth when checking then returns false`() {
        assertFalse(
            isEntityStateTrustworthy(
                webSocketState = WebSocketState.ClosedAuth,
                hasActiveNetwork = true,
            ),
        )
    }
}

package io.homeassistant.companion.android.common.data.websocket

/**
 * Decides whether the live connection to Home Assistant is currently healthy enough that
 * cached or freshly-loaded data depending on it can be trusted right now.
 *
 * Used to gate two distinct decisions:
 *  - In `HaControlsProviderService`, whether cached entity state may be rendered as live
 *    in the system controls panel without a fresh fetch.
 *  - In `FrontendViewModel`, whether to silently retry a transient WebView load failure
 *    (since a healthy WebSocket means the network and authenticated session are fine, and
 *    the failure is most likely transient) instead of surfacing the error screen.
 *
 * The connection is trustworthy only when BOTH signals agree we are online:
 *  - [webSocketState] is [WebSocketState.Active], meaning the WebSocket handshake completed
 *    and background subscriptions are (or could be) delivering updates; AND
 *  - [hasActiveNetwork] is `true`, meaning Android's `ConnectivityManager` reports that the
 *    device has at least one usable transport right now.
 *
 * The second check is load-bearing because OkHttp only transitions the WebSocket state to
 * `Closed` after a ping/read timeout (up to ~45s after the physical link dropped), so during
 * that window `webSocketState` still reports `Active` even though the connection is dead.
 * Without the `hasActiveNetwork` gate the user could see stale data presented as live, or the
 * Frontend could enter an auto-retry loop that cannot succeed.
 */
fun isLiveConnectionTrustworthy(webSocketState: WebSocketState?, hasActiveNetwork: Boolean): Boolean =
    webSocketState == WebSocketState.Active && hasActiveNetwork

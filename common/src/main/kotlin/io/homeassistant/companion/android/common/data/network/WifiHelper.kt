package io.homeassistant.companion.android.common.data.network

interface WifiHelper {

    companion object {
        const val BSSID_PREFIX = "BSSID:"
        const val INVALID_BSSID = "02:00:00:00:00:00"

        /** WiFi signal strength threshold in dBm below which the connection is considered too weak */
        const val WEAK_WIFI_THRESHOLD = -80
    }

    /** Returns if the device exposes Wi-Fi adapter(s) to apps. To check if Wi-Fi is used, see [isUsingWifi]. */
    fun hasWifi(): Boolean

    /** Returns if the active data connection is using Wi-Fi */
    fun isUsingWifi(): Boolean

    /** Returns if the active data connection is using one of the provided Wi-Fi networks */
    fun isUsingSpecificWifi(networks: List<String>): Boolean
    fun getWifiSsid(): String?
    fun getWifiBssid(): String?

    /**
     * Returns the current WiFi signal strength in dBm (typically -30 to -100).
     * Returns null if not connected to WiFi or unable to determine signal strength.
     * Values closer to 0 are stronger, values closer to -100 are weaker.
     */
    fun getWifiSignalStrength(): Int?
}

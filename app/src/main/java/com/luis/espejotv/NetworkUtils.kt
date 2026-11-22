package com.luis.espejotv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * IP local del celular en la red WiFi (o en su propio hotspot).
     * Da prioridad a wlan / ap (hotspot) sobre datos móviles.
     */
    fun localIp(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { nif.name to it.hostAddress }
                }
            val preferred = candidates.firstOrNull {
                it.first.startsWith("wlan") || it.first.startsWith("ap") ||
                    it.first.startsWith("swlan") || it.first.startsWith("eth")
            }
            (preferred ?: candidates.firstOrNull())?.second
        } catch (e: Exception) {
            null
        }
    }
}

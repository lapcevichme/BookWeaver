package com.lapcevichme.bookweaverdesktop.util

import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    fun getAllLocalIPs(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
        .toList()
}
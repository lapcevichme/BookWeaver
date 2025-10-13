package com.lapcevichme.bookweaverdesktop.core.util

import mu.KotlinLogging
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

private val logger = KotlinLogging.logger {}

object NetworkUtils {
    /**
     * Возвращает список всех локальных не-петлевых IPv4 адресов.
     * Это полезно для отображения QR-кода и настройки mDNS.
     * @return Список [InetAddress] или пустой список в случае ошибки.
     */
    fun getAllLocalIPs(): List<InetAddress> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .toList()
        } catch (e: SocketException) {
            logger.error(e) { "Could not retrieve network interfaces." }
            emptyList()
        }
    }
}

package com.lapcevichme.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import javax.inject.Inject

/**
 * A helper class to discover network services using NSD.
 * This class is now managed by Hilt.
 */
class NsdHelper @Inject constructor(context: Context) {
    private var nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscoveryActive = false

    fun discoverServices(
        serviceType: String,
        onServiceFound: (NsdServiceInfo) -> Unit,
        onDiscoveryStopped: () -> Unit
    ) {
        if (isDiscoveryActive) {
            Log.d("NsdHelper", "Discovery is already active. Ignoring new request.")
            return
        }

        Log.d("NsdHelper", "Starting service discovery...")
        isDiscoveryActive = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NsdHelper", "NSD discovery started successfully.")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("NsdHelper", "Service found: ${service.serviceName}. Resolving...")
                if (service.host == null) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(
                                "NsdHelper",
                                "Failed to resolve service '${serviceInfo.serviceName}': error code $errorCode"
                            )
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d(
                                "NsdHelper",
                                "Service resolved: ${serviceInfo.host}:${serviceInfo.port}"
                            )
                            stopDiscovery()
                            onServiceFound(serviceInfo)
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d("NsdHelper", "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(type: String) {
                Log.d("NsdHelper", "NSD discovery stopped.")
                isDiscoveryActive = false
                discoveryListener = null
                onDiscoveryStopped()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NsdHelper", "START DISCOVERY FAILED, error code: $errorCode")
                isDiscoveryActive = false
                discoveryListener = null
                onDiscoveryStopped()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NsdHelper", "STOP DISCOVERY FAILED, error code: $errorCode")
                isDiscoveryActive = false
                discoveryListener = null
            }
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        if (isDiscoveryActive && discoveryListener != null) {
            Log.d("NsdHelper", "Stopping service discovery...")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e("NsdHelper", "Error stopping discovery", e)
            }
        }
        isDiscoveryActive = false
        discoveryListener = null
    }
}

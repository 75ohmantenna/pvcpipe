package org.schabi.newpipe.player.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Collections
import java.util.Locale
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.ProtocolType

/**
 * FCast-only NSD, scoped to the open device picker. The SDK's Android discoverer also browses
 * Google Cast and cannot be closed, so resolved Android NSD records are passed to the SDK instead.
 */
class FCastDiscovery(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onDevicesChanged(devices: List<FCastDevice>)
        fun onError(message: String)
    }

    private val context = context.applicationContext
    private val nsd = this.context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val services = linkedMapOf<String, Service>()
    private val devices = linkedMapOf<String, FCastDevice>()
    private val pending = ArrayDeque<Service>()
    private var resolving: Service? = null
    private var resolveTimeout: Runnable? = null
    private var discoveryRegistered = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false
    private var closed = false

    private class Service(val info: NsdServiceInfo) {
        var attempts = 0

        // Kept as Any so API 26 does not need to load the API 34 callback class.
        var callback: Any? = null
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = onMain {
            discoveryRegistered = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onMain {
            if (!closed) {
                listener.onError("FCast discovery failed ($errorCode)")
                closeOnMain()
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = onMain {
            Log.w(TAG, "Could not stop FCast discovery: $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) = onMain {
            if (closed || !started || serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE) return@onMain
            val name = serviceInfo.serviceName?.takeIf { it.isNotEmpty() } ?: return@onMain
            if (services.containsKey(name)) return@onMain
            val service = Service(serviceInfo)
            services[name] = service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                trackService(service)
            } else {
                pending.addLast(service)
                resolveNext()
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = onMain {
            if (!closed) serviceInfo.serviceName?.let(::removeService)
        }
    }

    fun start() = onMain {
        if (started || closed) return@onMain
        started = true
        try {
            // Older Android versions do not automatically enable multicast reception for NSD.
            multicastLock = context.getSystemService(WifiManager::class.java)
                ?.createMulticastLock(TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discoveryRegistered = true
        } catch (error: Exception) {
            listener.onError(error.message ?: "Could not start FCast discovery")
            closeOnMain()
        }
    }

    override fun close() = onMain { closeOnMain() }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun trackService(service: Service) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onMain {
                if (isCurrent(service)) publish(service, serviceInfo)
            }

            override fun onServiceLost() = onMain {
                if (isCurrent(service)) removeService(service.info.serviceName)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = onMain {
                if (isCurrent(service)) {
                    service.callback = null
                    services.remove(service.info.serviceName)
                    listener.onError("Could not resolve FCast receiver (${service.info.serviceName}: $errorCode)")
                }
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        service.callback = callback
        try {
            nsd.registerServiceInfoCallback(service.info, { main.post(it) }, callback)
        } catch (error: Exception) {
            service.callback = null
            services.remove(service.info.serviceName)
            listener.onError(error.message ?: "Could not resolve FCast receiver")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (closed || resolving != null) return
        while (pending.isNotEmpty()) {
            val service = pending.removeFirst()
            if (!isCurrent(service)) continue
            resolving = service
            resolveLegacy(service)
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(service: Service) {
        if (closed || resolving !== service) return
        service.attempts++
        val deadline = Runnable {
            if (!closed && resolving === service) {
                if (isCurrent(service)) listener.onError("FCast receiver resolution timed out")
                finishResolve(service)
            }
        }
        resolveTimeout = deadline
        main.postDelayed(deadline, RESOLVE_TIMEOUT_MS)
        try {
            nsd.resolveService(
                service.info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = onMain {
                        // A late result may update the record, but must not advance a newer resolve.
                        if (isCurrent(service)) publish(service, serviceInfo)
                        finishResolve(service)
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onMain {
                        if (closed || resolving !== service) return@onMain
                        resolveTimeout?.let(main::removeCallbacks)
                        resolveTimeout = null
                        if (isCurrent(service) && errorCode == NsdManager.FAILURE_ALREADY_ACTIVE &&
                            service.attempts < MAX_RESOLVE_ATTEMPTS
                        ) {
                            main.postDelayed({ resolveLegacy(service) }, RESOLVE_RETRY_MS * service.attempts)
                        } else {
                            if (isCurrent(service)) {
                                listener.onError("Could not resolve FCast receiver (${service.info.serviceName}: $errorCode)")
                            }
                            finishResolve(service)
                        }
                    }
                }
            )
        } catch (error: Exception) {
            if (isCurrent(service)) listener.onError(error.message ?: "Could not resolve FCast receiver")
            finishResolve(service)
        }
    }

    private fun finishResolve(service: Service) {
        if (resolving !== service) return
        resolveTimeout?.let(main::removeCallbacks)
        resolveTimeout = null
        resolving = null
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun publish(service: Service, resolved: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolved.hostAddresses
        } else {
            listOfNotNull(resolved.host)
        }
        if (addresses.isEmpty() || resolved.port !in 1..65535) return
        // Do not publish an unresolved onServiceFound record: it has no fp TXT identity yet.
        val txt = resolved.attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
        val info = DeviceInfo(
            service.info.serviceName,
            ProtocolType.F_CAST,
            addresses.map { it.toFCastAddress() },
            resolved.port.toUShort(),
            txt
        )
        val device = FCastDevice(info)
        if (devices[info.name]?.info == device.info) return
        devices[info.name] = device
        notifyDevices()
    }

    private fun removeService(name: String) {
        val service = services.remove(name) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) unregister(service)
        if (devices.remove(name) != null) notifyDevices()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregister(service: Service) {
        val callback = service.callback as? NsdManager.ServiceInfoCallback ?: return
        service.callback = null
        try {
            nsd.unregisterServiceInfoCallback(callback)
        } catch (error: Exception) {
            Log.w(TAG, "Could not unregister FCast service callback", error)
        }
    }

    private fun closeOnMain() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        pending.clear()
        resolving = null
        resolveTimeout = null
        if (discoveryRegistered) {
            discoveryRegistered = false
            try {
                nsd.stopServiceDiscovery(discoveryListener)
            } catch (error: Exception) {
                Log.w(TAG, "Could not stop FCast discovery", error)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            services.values.forEach(::unregister)
        }
        services.clear()
        devices.clear()
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not release FCast multicast lock", error)
            }
        }
        multicastLock = null
    }

    private fun notifyDevices() {
        if (!closed) {
            listener.onDevicesChanged(
                Collections.unmodifiableList(devices.values.sortedBy { it.name.lowercase(Locale.ROOT) })
            )
        }
    }

    private fun isCurrent(service: Service) = !closed && services[service.info.serviceName] === service

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private companion object {
        const val TAG = "FCastDiscovery"
        const val SERVICE_TYPE = "_fcast._tcp"
        const val RESOLVE_TIMEOUT_MS = 10_000L
        const val MAX_RESOLVE_ATTEMPTS = 3
        const val RESOLVE_RETRY_MS = 500L
    }
}

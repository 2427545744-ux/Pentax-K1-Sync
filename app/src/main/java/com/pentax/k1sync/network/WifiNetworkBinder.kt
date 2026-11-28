package com.pentax.k1sync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.PowerManager
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * Dual-Network Concurrency Manager for Pentax K-1:
 * 1. Uses Android 10+ local-only WifiNetworkSpecifier WITH WPA2 passphrase so Android connects
 *    to PENTAX_xxxx as an app-scoped peer Wi-Fi WITHOUT dropping the phone's 4G/5G internet!
 * 2. Binds ONLY camera sockets (OkHttp + Live View TCP) to the K-1 Wi-Fi via network.socketFactory,
 *    never hijacking the system's internet route.
 * 3. Requests a concurrent Cellular Internet keep-alive network so Android keeps 4G/5G active.
 */
class WifiNetworkBinder(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val boostLock = Any()
    private var activeWifiLock: WifiManager.WifiLock? = null
    private var activeWakeLock: PowerManager.WakeLock? = null
    private var cellularPausedForTransfer: Boolean = false

    private val _boundNetwork = MutableStateFlow<Network?>(null)
    val boundNetwork: StateFlow<Network?> = _boundNetwork.asStateFlow()

    private val _cellularInternetAlive = MutableStateFlow(false)
    val cellularInternetAlive: StateFlow<Boolean> = _cellularInternetAlive.asStateFlow()

    private val _isLocalOnlyP2p = MutableStateFlow(false)
    val isLocalOnlyP2p: StateFlow<Boolean> = _isLocalOnlyP2p.asStateFlow()

    private val _statusMessage = MutableStateFlow("未绑定相机热点")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var activeWifiCallback: ConnectivityManager.NetworkCallback? = null
    private var activeCellularCallback: ConnectivityManager.NetworkCallback? = null

    fun getSocketFactory(): SocketFactory {
        return _boundNetwork.value?.socketFactory ?: SocketFactory.getDefault()
    }

    fun getDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val net = _boundNetwork.value
            return if (net != null) {
                try {
                    net.getAllByName(hostname).toList()
                } catch (_: Exception) {
                    InetAddress.getAllByName(hostname).toList()
                }
            } else {
                InetAddress.getAllByName(hostname).toList()
            }
        }
    }

    fun isRouteBound(): Boolean = _boundNetwork.value != null

    /**
     * Requests Android to keep the 4G/5G cellular data link alive concurrently while Wi-Fi is attached
     * to a no-internet camera hotspot.
     */
    private fun startCellularKeepAlive() {
        stopCellularKeepAlive()
        val cellReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                _cellularInternetAlive.value = true
                updateDualNetStatus()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                _cellularInternetAlive.value = false
                updateDualNetStatus()
            }

            override fun onUnavailable() {
                super.onUnavailable()
                _cellularInternetAlive.value = false
            }
        }

        activeCellularCallback = cb
        try {
            connectivityManager.requestNetwork(cellReq, cb)
        } catch (_: Exception) {
        }
    }

    private fun stopCellularKeepAlive() {
        activeCellularCallback?.let { cb ->
            try { connectivityManager.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        activeCellularCallback = null
        _cellularInternetAlive.value = false
    }

    private fun updateDualNetStatus() {
        if (_boundNetwork.value == null) return
        val mode = if (_isLocalOnlyP2p.value) "双网并发直连" else "已绑定相机 Wi-Fi"
        _statusMessage.value = if (_cellularInternetAlive.value) {
            "$mode · 5G 移动上网在线"
        } else {
            "$mode（192.168.0.1 独立路由）"
        }
    }

    /**
     * Binds camera sockets to the currently connected Wi-Fi network WITHOUT calling bindProcessToNetwork
     * (socketFactory routes 192.168.0.1 cleanly over wlan0 while keeping cellular alive).
     */
    fun bindToCurrentWifi(onBound: (Boolean) -> Unit = {}) {
        releaseBinding(silent = true)
        _isLocalOnlyP2p.value = false

        // First check if a Wi-Fi network is already active among allNetworks (instant, no wait!)
        @Suppress("DEPRECATION")
        val existingWifi = try {
            connectivityManager.allNetworks.firstOrNull { net ->
                val caps = connectivityManager.getNetworkCapabilities(net)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) { null }

        if (existingWifi != null) {
            _boundNetwork.value = existingWifi
            startCellularKeepAlive()
            updateDualNetStatus()
            onBound(true)
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val delivered = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                _boundNetwork.value = network
                startCellularKeepAlive()
                updateDualNetStatus()
                if (delivered.compareAndSet(false, true)) {
                    onBound(true)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (_boundNetwork.value == network) {
                    _boundNetwork.value = null
                    stopCellularKeepAlive()
                    _statusMessage.value = "相机 Wi-Fi 连接已断开"
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                _statusMessage.value = "未检测到相机 Wi-Fi，请连入 PENTAX_xxxx"
                if (delivered.compareAndSet(false, true)) {
                    onBound(false)
                }
            }
        }

        activeWifiCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, 8000)
        } catch (e: Exception) {
            _statusMessage.value = "Wi-Fi 绑定异常: ${e.localizedMessage}"
            onBound(false)
        }
    }

    /**
     * Android 10+ App-Scoped Local-Only P2P Wi-Fi Connection to PENTAX_xxxx.
     * Passing the K-1 WPA2 passphrase allows Android to connect to PENTAX_xxxx strictly for this app
     * while keeping 4G/5G cellular internet working normally for WeChat, browser, and the whole phone!
     */
    fun requestPentaxHotspot(wpa2Passphrase: String?, onResult: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            bindToCurrentWifi(onResult)
            return
        }

        val cleanPass = wpa2Passphrase?.trim().orEmpty()
        if (cleanPass.length < 8) {
            // Without WPA2 passphrase, WifiNetworkSpecifier only matches open networks; fall back to current Wi-Fi
            _statusMessage.value = "未填 K-1 密码，尝试绑定当前已连 Wi-Fi..."
            bindToCurrentWifi(onResult)
            return
        }

        releaseBinding(silent = true)
        _isLocalOnlyP2p.value = true
        _statusMessage.value = "正在搜索 PENTAX_xxxx 热点（双网并发模式）..."

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("PENTAX_", PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(cleanPass)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val delivered = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Do NOT call bindProcessToNetwork: socketFactory routes 192.168.0.1 exclusively
                // over this local-only Wi-Fi while the phone keeps full 4G/5G internet!
                _boundNetwork.value = network
                startCellularKeepAlive()
                updateDualNetStatus()
                if (delivered.compareAndSet(false, true)) {
                    onResult(true)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                _isLocalOnlyP2p.value = false
                _statusMessage.value = "直连未完成（请核对 K-1 密码或在系统设置忘记旧热点），尝试绑定当前 Wi-Fi"
                if (delivered.compareAndSet(false, true)) {
                    bindToCurrentWifi(onResult)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (_boundNetwork.value == network) {
                    _boundNetwork.value = null
                    _isLocalOnlyP2p.value = false
                    stopCellularKeepAlive()
                    _statusMessage.value = "与 K-1 热点断开连接"
                }
            }
        }

        activeWifiCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (e: Exception) {
            _isLocalOnlyP2p.value = false
            bindToCurrentWifi(onResult)
        }
    }

    /**
     * Acquires Android High-Performance / Low-Latency WifiLock + Partial WakeLock during transfers
     * so the phone's Wi-Fi chip does NOT enter Power-Save Mode (PSM) on a no-internet camera hotspot!
     * Optionally suspends 5G cellular keep-alive during bulk transfers to eliminate radio time-slicing.
     */
    fun acquireTransferBoost(pauseCellularKeepAlive: Boolean = false) {
        synchronized(boostLock) {
            try {
                if (activeWifiLock == null) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    activeWifiLock = wifiManager?.createWifiLock(mode, "PentaxK1Sync:FastTransfer")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (activeWifiLock?.isHeld == false) {
                    activeWifiLock?.acquire()
                }
            } catch (_: Exception) {
            }
            try {
                if (activeWakeLock == null) {
                    activeWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PentaxK1Sync:FastTransferCpu")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (activeWakeLock?.isHeld == false) {
                    activeWakeLock?.acquire(15 * 60 * 1000L)
                }
            } catch (_: Exception) {
            }
            if (pauseCellularKeepAlive && activeCellularCallback != null) {
                cellularPausedForTransfer = true
                stopCellularKeepAlive()
                updateDualNetStatus()
            }
        }
    }

    fun releaseTransferBoost() {
        synchronized(boostLock) {
            try {
                if (activeWifiLock?.isHeld == true) activeWifiLock?.release()
            } catch (_: Exception) {
            }
            try {
                if (activeWakeLock?.isHeld == true) activeWakeLock?.release()
            } catch (_: Exception) {
            }
            if (cellularPausedForTransfer) {
                cellularPausedForTransfer = false
                if (_boundNetwork.value != null) {
                    startCellularKeepAlive()
                }
            }
        }
    }

    fun releaseBinding(silent: Boolean = false) {
        releaseTransferBoost()
        activeWifiCallback?.let { cb ->
            try { connectivityManager.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        activeWifiCallback = null
        stopCellularKeepAlive()
        try { connectivityManager.bindProcessToNetwork(null) } catch (_: Exception) {}
        _boundNetwork.value = null
        _isLocalOnlyP2p.value = false
        if (!silent) {
            _statusMessage.value = "已释放相机热点路由 · 移动网络已恢复"
        }
    }
}

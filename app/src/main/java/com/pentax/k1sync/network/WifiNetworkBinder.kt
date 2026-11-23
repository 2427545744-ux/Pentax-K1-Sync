package com.pentax.k1sync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.net.SocketFactory

class WifiNetworkBinder(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _boundNetwork = MutableStateFlow<Network?>(null)
    val boundNetwork: StateFlow<Network?> = _boundNetwork.asStateFlow()

    private val _statusMessage = MutableStateFlow("未绑定相机热点")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun getSocketFactory(): SocketFactory {
        return _boundNetwork.value?.socketFactory ?: SocketFactory.getDefault()
    }

    fun bindToCurrentWifi(onBound: (Boolean) -> Unit = {}) {
        releaseBinding()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val bound = connectivityManager.bindProcessToNetwork(network)
                _boundNetwork.value = network
                _statusMessage.value = if (bound) "已锁定相机 Wi-Fi 路由（防切 5G）" else "已连接 Wi-Fi"
                onBound(true)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (_boundNetwork.value == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    _boundNetwork.value = null
                    _statusMessage.value = "相机 Wi-Fi 连接已断开"
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                _statusMessage.value = "未检测到相机 Wi-Fi，请连入 PENTAX_xxxx"
                onBound(false)
            }
        }

        activeCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, 8000)
        } catch (e: Exception) {
            _statusMessage.value = "Wi-Fi 绑定异常: ${e.localizedMessage}"
            onBound(false)
        }
    }

    fun requestPentaxHotspot(onResult: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            bindToCurrentWifi(onResult)
            return
        }

        releaseBinding()
        _statusMessage.value = "正在搜索 PENTAX_xxxx 热点..."

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("PENTAX_", PatternMatcher.PATTERN_PREFIX))
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                connectivityManager.bindProcessToNetwork(network)
                _boundNetwork.value = network
                _statusMessage.value = "已直连 PENTAX K-1 热点并锁定路由"
                onResult(true)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                _statusMessage.value = "尝试绑定当前已连 Wi-Fi..."
                bindToCurrentWifi(onResult)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (_boundNetwork.value == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    _boundNetwork.value = null
                    _statusMessage.value = "与 K-1 热点断开连接"
                }
            }
        }

        activeCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (e: Exception) {
            bindToCurrentWifi(onResult)
        }
    }

    fun releaseBinding() {
        activeCallback?.let { cb ->
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        activeCallback = null
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        _boundNetwork.value = null
    }
}

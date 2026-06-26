package com.junai.app

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.TextView

/**
 * Monitors internet connectivity and updates the network dot + label in the toolbar.
 * Call start() in onCreate after views are ready, stop() in onDestroy.
 */
class NetworkStatusMonitor(
    private val activity: Activity,
    private val networkDot: TextView,
    private val networkStatusText: TextView
) {
    private val connectivityManager =
        activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var dotAnimator: ObjectAnimator? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activity.runOnUiThread { updateUi(true) }
        }
        override fun onLost(network: Network) {
            activity.runOnUiThread { updateUi(false) }
        }
        override fun onUnavailable() {
            activity.runOnUiThread { updateUi(false) }
        }
    }

    fun start() {
        // Set initial state immediately
        val isConnected = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        updateUi(isConnected)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { connectivityManager.registerNetworkCallback(request, callback) } catch (_: Exception) {}
    }

    fun stop() {
        try { connectivityManager.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        dotAnimator?.cancel()
    }

    private fun updateUi(isOnline: Boolean) {
        val color = if (isOnline) 0xFF43A047L.toInt() else 0xFFE53935L.toInt()
        networkDot.setTextColor(color)
        networkStatusText.text = if (isOnline) "Online" else "Offline"
        networkStatusText.setTextColor(color)

        dotAnimator?.cancel()
        dotAnimator = ObjectAnimator.ofFloat(networkDot, "alpha", 1f, 0.2f, 1f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }
}

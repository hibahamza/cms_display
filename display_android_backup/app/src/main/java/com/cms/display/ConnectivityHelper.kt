package com.cms.display

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}

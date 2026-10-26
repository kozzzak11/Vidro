package app.vidro.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun Context.isMobileData(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(network) ?: return false
    val unmetered = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !unmetered
}

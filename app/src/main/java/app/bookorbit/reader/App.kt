package app.bookorbit.reader

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class App : Application(), ImageLoaderFactory {
    lateinit var session: Session
    lateinit var api: BookOrbitApi
    lateinit var outbox: Outbox
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Whether the device has a network connection at all (not whether the server is reachable). */
    val networkUp = MutableStateFlow(true)

    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        api = BookOrbitApi(session, cacheDir, filesDir)
        outbox = Outbox(File(filesDir, "outbox.json"))
        api.outbox = outbox

        // Whenever a connection appears, deliver whatever is waiting.
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkUp.value = true
                if (session.isLoggedIn) syncNow()
            }

            override fun onLost(network: Network) {
                networkUp.value = false
            }
        })
        // The server coming back (not just Wi-Fi) is also a reason to deliver what is waiting.
        appScope.launch { api.online.collect { reachable -> if (reachable && session.isLoggedIn && !outbox.isEmpty()) syncNow() } }
        if (session.isLoggedIn && !outbox.isEmpty()) syncNow()
    }

    // Covers are kept on disk and used as they are, so they still show with no connection.
    override fun newImageLoader() = ImageLoader.Builder(this)
        .okHttpClient { api.client }
        .respectCacheHeaders(false)
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "covers")).maxSizeBytes(150L * 1024 * 1024).build() }
        .build()
}

package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DacpQueueState(
    val previous: TrackInfo? = null,
    val upNext: List<TrackInfo> = emptyList(),
    val supported: Boolean = false,
) {
    val next: TrackInfo? get() = upNext.firstOrNull()
}

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 * Resolves the sender's control port via mDNS, then sends HTTP GET requests.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile var dacpId = ""
    @Volatile var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0
    private val _queue = MutableStateFlow(DacpQueueState())
    val queue: StateFlow<DacpQueueState> = _queue.asStateFlow()

    fun update(dacpId: String, activeRemote: String) {
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        _resolve()
    }

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun playQueueItem(relativeIndex: Int): ListenableFuture<Unit> {
        require(relativeIndex in 1..QUEUE_WINDOW) { "queue index out of range" }
        return _send("/ctrl-int/1/playqueue-edit?command=playnow&index=$relativeIndex")
    }
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")
    fun muteToggle() = _send("/ctrl-int/1/mutetoggle")
    fun beginFastForward() = _send("/ctrl-int/1/beginff")
    fun beginRewind() = _send("/ctrl-int/1/beginrew")
    fun playResume() = _send("/ctrl-int/1/playresume")

    /** Best-effort queue metadata. Music exposes this on most DACP sessions. */
    fun refreshQueue(current: TrackInfo? = null) {
        if (host.isEmpty() || activeRemote.isEmpty()) return
        try {
            exec.execute {
                val previousResponse = runCatching {
                    _request("/ctrl-int/1/playqueue-contents?span=-1")
                }
                val nextResponse = runCatching {
                    // Some DACP senders count the current item in the positive
                    // span while others return only future rows. One extra slot
                    // makes both behaviours yield the same queue window.
                    _request("/ctrl-int/1/playqueue-contents?span=${QUEUE_WINDOW + 1}")
                }
                val previousItems = previousResponse.getOrNull()
                    ?.let(DmapParser::parseQueueItems)
                    .orEmpty()
                val nextItems = nextResponse.getOrNull()
                    ?.let(DmapParser::parseQueueItems)
                    .orEmpty()
                val upcoming = nextItems
                    .let { items ->
                        if (items.firstOrNull()?.sameSongAs(current) == true) items.drop(1) else items
                    }
                    .take(QUEUE_WINDOW)
                _queue.value = DacpQueueState(
                    previous = previousItems.lastOrNull { !it.sameSongAs(current) },
                    upNext = upcoming,
                    supported = previousResponse.isSuccess || nextResponse.isSuccess,
                )
            }
        } catch (_: Exception) {
            // The controller is being released; the UI will keep its local
            // previous-track fallback and show an honest unavailable state.
        }
    }

    fun release() {
        exec.shutdownNow()
        _queue.value = DacpQueueState()
    }

    private fun _resolve() {
        if (dacpId.isEmpty()) return
        val serviceName = "iTunes_Ctrl_$dacpId"
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            this.serviceName = serviceName
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "DACP resolve failed: $code")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    host = si.host.hostAddress ?: return
                    port = si.port
                    Log.i(TAG, "DACP resolved: $host:$port")
                    refreshQueue()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
        }
    }

    private fun _send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        if (host.isEmpty() || activeRemote.isEmpty()) {
            result.setException(IOException("dacp endpoint not resolved"))
            return result
        }
        try {
            exec.execute {
                try {
                    _request(path)
                    result.set(Unit)
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                    result.setException(e)
                }
            }
        } catch (e: Exception) {
            result.setException(e)
        }
        return result
    }

    private fun _request(path: String): ByteArray {
        val requestHost = host
        val requestPort = port
        val requestRemote = activeRemote
        if (requestHost.isEmpty() || requestRemote.isEmpty()) {
            throw IOException("dacp endpoint not resolved")
        }
        val url = "http://$requestHost:$requestPort$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Active-Remote", requestRemote)
        conn.setRequestProperty("Host", "$requestHost:$requestPort")
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_500
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "DACP $path -> HTTP $code")
                throw IOException("HTTP $code")
            }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun TrackInfo.sameSongAs(other: TrackInfo?): Boolean = other != null &&
        title.equals(other.title, ignoreCase = true) &&
        artist.equals(other.artist, ignoreCase = true) &&
        album.equals(other.album, ignoreCase = true)

    companion object {
        private const val TAG = "DacpController"
        private const val QUEUE_WINDOW = 50
    }
}

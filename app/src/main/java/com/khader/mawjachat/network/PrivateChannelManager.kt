package com.khader.mawjachat.network

import android.net.Uri
import com.google.gson.Gson
import com.khader.mawjachat.model.Envelope
import com.khader.mawjachat.model.WaveMessagePacket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PrivateChannelManager(
    private val senderName: String,
    private val receiverName: String,
    private val sharedCode: String,
    private val relayUrl: String,
    private val deviceId: String,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStatus(text: String, connected: Boolean)
        fun onPacket(packet: WaveMessagePacket)
        fun onPeerPresence(online: Boolean)
        fun onNameTaken()
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closedByUser = false
    @Volatile private var nameTaken = false
    @Volatile private var reconnectPending = false
    @Volatile private var active = false
    @Volatile private var peerOnline = false
    @Volatile private var peerLastSeen = 0L
    @Volatile private var conflictWarned = false
    private var reconnectAttempts = 0
    private var heartbeat: ScheduledFuture<*>? = null

    val roomId: String = generatePrivateRoomId(senderName, receiverName, sharedCode)

    companion object {
        private const val PEER_TIMEOUT_MS = 26_000L
        private const val HEARTBEAT_SEC = 8L

        fun generatePrivateRoomId(userA: String, userB: String, code: String): String {
            val cleanA = userA.trim().lowercase()
            val cleanB = userB.trim().lowercase()
            val cleanCode = code.trim()
            val sortedPair = listOf(cleanA, cleanB).sorted().joinToString(":")
            val rawKey = "MAWJA_PRIVATE|$sortedPair|$cleanCode"
            val digest = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }

    fun connect() {
        closedByUser = false
        openSocket()
        if (heartbeat == null) {
            heartbeat = scheduler.scheduleWithFixedDelay({ tick() }, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS)
        }
    }

    private fun openSocket() {
        callbacks.onStatus("جاري تأمين القناة الخاصة...", false)
        val sep = if (relayUrl.contains("?")) "&" else "?"
        val url = relayUrl + sep +
                "room=" + Uri.encode(roomId) +
                "&user=" + Uri.encode(senderName.trim()) +
                "&device=" + Uri.encode(deviceId)
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            callbacks.onStatus("رابط خادم البث غير صالح (راجع الإعدادات)", false)
            return
        }
        webSocket = client.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            callbacks.onStatus("متصل بقناة خاصة مشفّرة (#${roomId.take(6)})", true)
            send(Envelope(t = "hello", user = senderName, device = deviceId, active = active))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncoming(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            callbacks.onStatus("وضع المعاينة المحلية (تحقق من خادم البث في الإعدادات)", false)
            handleDrop()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (code == 4001) fireNameTaken()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == 4001) {
                fireNameTaken()
            } else if (!closedByUser) {
                callbacks.onStatus("تم إغلاق القناة الخاصة", false)
                handleDrop()
            }
        }
    }

    private fun handleIncoming(text: String) {
        val env = try {
            gson.fromJson(text, Envelope::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        if (env.t == "error") {
            if (env.code == "name_taken") fireNameTaken()
            return
        }
        if (env.device == deviceId) return // صدى رسائلنا من خادم echo

        val fromPeer = env.user != null &&
                env.user.trim().equals(receiverName.trim(), ignoreCase = true)

        // نفس اسمي لكن من جهاز آخر داخل نفس القناة
        if (env.user != null && env.user.trim().equals(senderName.trim(), ignoreCase = true) && !conflictWarned) {
            conflictWarned = true
            callbacks.onStatus("⚠ هذا الاسم مستخدم من جهاز آخر في نفس القناة", true)
        }

        when (env.t) {
            "wave" -> {
                val p = env.packet ?: return
                if (p.roomId == roomId && !p.senderName.equals(senderName, ignoreCase = true)) {
                    callbacks.onPacket(p)
                }
            }
            "hello" -> {
                if (fromPeer) markPeer(env.active == true)
                sendPresence()
            }
            "presence" -> if (fromPeer) markPeer(env.active == true)
            "bye" -> if (fromPeer) setPeer(false)
        }
    }

    private fun markPeer(isActive: Boolean) {
        peerLastSeen = System.currentTimeMillis()
        setPeer(isActive)
    }

    private fun setPeer(online: Boolean) {
        if (peerOnline != online) {
            peerOnline = online
            callbacks.onPeerPresence(online)
        }
    }

    private fun tick() {
        sendPresence()
        if (peerOnline && System.currentTimeMillis() - peerLastSeen > PEER_TIMEOUT_MS) setPeer(false)
    }

    private fun sendPresence() {
        send(Envelope(t = "presence", user = senderName, device = deviceId, active = active))
    }

    /** يُستدعى عندما يدخل التطبيق الواجهة الأمامية أو يخرج منها. */
    fun setActive(isActive: Boolean) {
        active = isActive
        sendPresence()
    }

    private fun send(env: Envelope): Boolean = webSocket?.send(gson.toJson(env)) ?: false

    fun sendWavePacket(packet: WaveMessagePacket): Boolean =
        send(Envelope(t = "wave", user = senderName, device = deviceId, packet = packet))

    private fun handleDrop() {
        setPeer(false)
        if (closedByUser || nameTaken || reconnectPending) return
        reconnectPending = true
        val delaySec = minOf(30L, 3L * (1 shl minOf(reconnectAttempts, 3)))
        reconnectAttempts++
        try {
            scheduler.schedule({
                reconnectPending = false
                if (!closedByUser && !nameTaken) openSocket()
            }, delaySec, TimeUnit.SECONDS)
        } catch (_: Exception) {
            reconnectPending = false
        }
    }

    private fun fireNameTaken() {
        if (nameTaken) return
        nameTaken = true
        callbacks.onNameTaken()
    }

    fun disconnect() {
        if (closedByUser) return
        closedByUser = true
        send(Envelope(t = "bye", user = senderName, device = deviceId))
        heartbeat?.cancel(false)
        heartbeat = null
        webSocket?.close(1000, "User left private room")
        webSocket = null
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }
}

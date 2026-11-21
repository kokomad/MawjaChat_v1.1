package com.khader.mawjachat.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.khader.mawjachat.R
import com.khader.mawjachat.data.ChatStore
import com.khader.mawjachat.data.UserDirectory
import com.khader.mawjachat.model.WaveMessagePacket
import com.khader.mawjachat.network.PrivateChannelManager
import com.khader.mawjachat.ui.ChatActivity

/**
 * خدمة أمامية تُبقي الاتصال بالقناة حيّاً حتى لو خرج المستخدم من التطبيق،
 * وتُظهر إشعاراً عند وصول رسالة موجية جديدة.
 */
class ChatService : Service() {

    companion object {
        const val ACTION_STOP = "com.khader.mawjachat.STOP"
        const val CH_SERVICE = "mawja_service"
        const val CH_MESSAGES = "mawja_messages"
        const val NOTIF_SERVICE = 1001
        const val NOTIF_MSG = 2001

        private const val EXTRA_SENDER = "SENDER_NAME"
        private const val EXTRA_RECEIVER = "RECEIVER_NAME"
        private const val EXTRA_CODE = "SHARED_CODE"

        fun start(ctx: Context, sender: String, receiver: String, code: String) {
            ChatStore.pendingError = null
            val i = Intent(ctx, ChatService::class.java)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_RECEIVER, receiver)
                .putExtra(EXTRA_CODE, code)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ChatStore.reset()
            ctx.stopService(Intent(ctx, ChatService::class.java))
            NotificationManagerCompat.from(ctx).cancel(NOTIF_MSG)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sender = ""
    private var receiver = ""
    private var code = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        sender = intent.getStringExtra(EXTRA_SENDER) ?: ""
        receiver = intent.getStringExtra(EXTRA_RECEIVER) ?: ""
        code = intent.getStringExtra(EXTRA_CODE) ?: ""
        createChannels()
        promoteToForeground()

        val key = PrivateChannelManager.generatePrivateRoomId(sender, receiver, code) + "|" + sender.lowercase()
        if (ChatStore.roomKey == key && ChatStore.channel != null) return START_NOT_STICKY

        ChatStore.reset()
        ChatStore.roomKey = key

        val prefs = getSharedPreferences("MawjaPrefs", MODE_PRIVATE)
        val relayUrl = prefs.getString("relay_url", "wss://echo.websocket.events") ?: "wss://echo.websocket.events"

        val channel = PrivateChannelManager(
            senderName = sender,
            receiverName = receiver,
            sharedCode = code,
            relayUrl = relayUrl,
            deviceId = UserDirectory(this).deviceId,
            callbacks = object : PrivateChannelManager.Callbacks {
                override fun onStatus(text: String, connected: Boolean) {
                    main.post {
                        ChatStore.status = text
                        if (!connected) ChatStore.peerOnline = false
                        ChatStore.listener?.onStatus(text)
                        if (!connected) ChatStore.listener?.onPeer(false)
                    }
                }

                override fun onPacket(packet: WaveMessagePacket) {
                    main.post { handlePacket(packet) }
                }

                override fun onPeerPresence(online: Boolean) {
                    main.post {
                        ChatStore.peerOnline = online
                        ChatStore.listener?.onPeer(online)
                    }
                }

                override fun onNameTaken() {
                    main.post {
                        ChatStore.pendingError = "name_taken"
                        ChatStore.listener?.onError("name_taken")
                        shutdown()
                    }
                }
            }
        )
        ChatStore.channel = channel
        channel.connect()
        channel.setActive(ChatStore.uiVisible)
        return START_NOT_STICKY
    }

    private fun handlePacket(packet: WaveMessagePacket) {
        ChatStore.add(packet)
        ChatStore.listener?.onMessage(packet)
        if (!ChatStore.uiVisible) {
            ChatStore.unread++
            notifyMessage(packet)
        }
    }

    private fun openChatIntent(): PendingIntent {
        val i = Intent(this, ChatActivity::class.java).apply {
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_RECEIVER, receiver)
            putExtra(EXTRA_CODE, code)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    private fun notifyMessage(packet: WaveMessagePacket) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val text = if (ChatStore.unread > 1) {
            "${ChatStore.unread} رسائل موجية جديدة"
        } else {
            "رسالة موجية جديدة (${packet.totalDurationMs / 1000.0}ث)"
        }
        val n = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setContentTitle(packet.senderName)
            .setContentText(text)
            .setColor(0xFF1FE5EE.toInt())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openChatIntent())
            .build()
        nm.notify(NOTIF_MSG, n)
    }

    private fun promoteToForeground() {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ChatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setContentTitle("موجة شات")
            .setContentText("متصل بقناة $sender ⇄ $receiver")
            .setColor(0xFF1FE5EE.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openChatIntent())
            .addAction(0, "قطع الاتصال", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_SERVICE, n)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "حالة الاتصال", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "الرسائل الموجية الجديدة", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
    }

    private fun shutdown() {
        ChatStore.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ChatStore.reset()
        super.onDestroy()
    }
}

package com.khader.mawjachat.data

import com.khader.mawjachat.model.WaveMessagePacket
import com.khader.mawjachat.network.PrivateChannelManager

/**
 * حالة الجلسة الحالية (الرسائل، حالة الطرف الآخر...).
 * تعيش داخل العملية طالما الخدمة تعمل، وكل الوصول إليها من الخيط الرئيسي فقط.
 */
object ChatStore {

    interface Listener {
        fun onMessage(p: WaveMessagePacket)
        fun onPeer(online: Boolean)
        fun onStatus(text: String)
        fun onError(code: String)
    }

    val messages = mutableListOf<WaveMessagePacket>()
    var channel: PrivateChannelManager? = null
    var roomKey: String? = null
    var peerOnline = false
    var status = ""
    var unread = 0
    var pendingError: String? = null
    var listener: Listener? = null

    var uiVisible = false
        private set

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) unread = 0
        channel?.setActive(visible)
    }

    fun add(p: WaveMessagePacket) {
        messages.add(p)
    }

    fun remove(id: String): Boolean = messages.removeAll { it.messageId == id }

    fun reset() {
        channel?.disconnect()
        channel = null
        roomKey = null
        peerOnline = false
        status = ""
        unread = 0
        messages.clear()
    }
}

package com.khader.mawjachat.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.khader.mawjachat.R
import com.khader.mawjachat.audio.AcousticWaveTranslator
import com.khader.mawjachat.audio.AudioRecorderManager
import com.khader.mawjachat.data.ChatStore
import com.khader.mawjachat.data.UserDirectory
import com.khader.mawjachat.databinding.ActivityChatBinding
import com.khader.mawjachat.model.WaveMessagePacket
import com.khader.mawjachat.network.PrivateChannelManager
import com.khader.mawjachat.service.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity(), ChatStore.Listener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var recorderManager: AudioRecorderManager

    private var senderName = ""
    private var receiverName = ""
    private var sharedCode = ""
    private var roomId = ""
    private var mediaPlayer: MediaPlayer? = null
    private var autoDelete = false
    private var errorDialogShown = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(BlockPatternDrawable())
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        senderName = intent.getStringExtra("SENDER_NAME") ?: "المرسل"
        receiverName = intent.getStringExtra("RECEIVER_NAME") ?: "المستقبل"
        sharedCode = intent.getStringExtra("SHARED_CODE") ?: "0000"
        roomId = PrivateChannelManager.generatePrivateRoomId(senderName, receiverName, sharedCode)

        binding.tvRoomTitle.text = "$senderName ⇄ $receiverName"

        val prefs = getSharedPreferences("MawjaPrefs", MODE_PRIVATE)
        autoDelete = prefs.getBoolean("auto_delete", false)
        binding.switchAutoDelete.isChecked = autoDelete
        binding.switchAutoDelete.setOnCheckedChangeListener { _, checked ->
            autoDelete = checked
            prefs.edit().putBoolean("auto_delete", checked).apply()
            Toast.makeText(
                this,
                if (checked) "سيتم حذف الرسائل الواردة تلقائياً بعد الاستماع إليها" else "الحذف اليدوي فقط",
                Toast.LENGTH_SHORT
            ).show()
        }

        adapter = MessageAdapter(
            currentUser = senderName,
            onPlayReconstructedAudio = { packet -> playReconstructedWave(packet) },
            onSaveWavFile = { packet -> exportWaveToWav(packet) },
            onDeleteMessage = { packet -> deleteMessage(packet.messageId, manual = true) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        // تشغيل الخدمة الأمامية التي تملك الاتصال (تبقى حيّة عند الخروج من التطبيق)
        ChatService.start(this, senderName, receiverName, sharedCode)

        recorderManager = AudioRecorderManager { amp ->
            runOnUiThread { binding.liveWaveformView.addLiveAmplitude(amp) }
        }

        binding.btnChatSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leaveChannel()
            }
        })

        binding.btnRecordHold.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                    binding.tvRecordingHint.text = "جاري التقاط الصوت وتحليل الموجات..."
                    recorderManager.startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    binding.tvRecordingHint.text = "اضغط مطولاً للتحدث وتحويل الصوت لموجات"
                    val pcmBytes = recorderManager.stopRecording()
                    binding.liveWaveformView.clearLive()
                    if (pcmBytes.size > 1600) {
                        processAndSendVoice(pcmBytes)
                    } else {
                        Toast.makeText(this, "الرسالة قصيرة جداً", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ChatStore.listener = this
        ChatStore.setUiVisible(true)
        NotificationManagerCompat.from(this).cancel(ChatService.NOTIF_MSG)

        adapter.setAll(ChatStore.messages)
        if (adapter.itemCount > 0) binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        binding.tvConnectionStatus.text = ChatStore.status.ifEmpty { "جاري الاتصال..." }
        applyPeer(ChatStore.peerOnline)
        ChatStore.pendingError?.let { onError(it) }
    }

    override fun onStop() {
        ChatStore.setUiVisible(false)
        ChatStore.listener = null
        super.onStop()
    }

    // ---------- ChatStore.Listener ----------

    override fun onMessage(p: WaveMessagePacket) {
        adapter.addMessage(p)
        binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
    }

    override fun onPeer(online: Boolean) = applyPeer(online)

    override fun onStatus(text: String) {
        binding.tvConnectionStatus.text = text
    }

    override fun onError(code: String) {
        if (code != "name_taken" || errorDialogShown) return
        errorDialogShown = true
        ChatStore.pendingError = null
        UserDirectory(this).forgetMyName(senderName)
        AlertDialog.Builder(this)
            .setTitle("الاسم مستخدم")
            .setMessage("الاسم \"$senderName\" محجوز لمستخدم آخر. اختر اسماً مختلفاً.")
            .setCancelable(false)
            .setPositiveButton("حسناً") { _, _ -> finish() }
            .show()
    }

    // ---------- واجهة ----------

    private fun applyPeer(online: Boolean) {
        binding.viewPeerDot.setBackgroundResource(if (online) R.drawable.dot_green else R.drawable.dot_red)
        binding.tvPeerPresence.text = if (online) "$receiverName • متصل" else "$receiverName • غير متصل"
        binding.tvPeerPresence.setTextColor(
            ContextCompat.getColor(this, if (online) R.color.presence_green else R.color.presence_red)
        )
    }

    private fun leaveChannel() {
        ChatService.stop(this)
        finish()
    }

    // ---------- إرسال / تشغيل / حذف ----------

    private fun processAndSendVoice(pcmBytes: ByteArray) {
        binding.tvRecordingHint.text = "جاري ترجمة الموجات (حروف، كلمات، سكون، فراغ)..."
        lifecycleScope.launch(Dispatchers.Default) {
            val packet = AcousticWaveTranslator.translatePcmToWavePacket(
                pcmBytes = pcmBytes,
                roomId = roomId,
                senderName = senderName,
                receiverName = receiverName
            )
            withContext(Dispatchers.Main) {
                val sent = ChatStore.channel?.sendWavePacket(packet) ?: false
                if (!sent) {
                    Toast.makeText(this@ChatActivity, "القناة غير متصلة، لم تصل الرسالة للطرف الآخر", Toast.LENGTH_SHORT).show()
                }
                ChatStore.add(packet)
                adapter.addMessage(packet)
                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                binding.tvRecordingHint.text = "اضغط مطولاً للتحدث وتحويل الصوت لموجات"
            }
        }
    }

    private fun playReconstructedWave(packet: WaveMessagePacket) {
        lifecycleScope.launch(Dispatchers.IO) {
            val wavFile = AcousticWaveTranslator.reconstructAudioFile(packet, cacheDir)
            withContext(Dispatchers.Main) {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(wavFile.absolutePath)
                        prepare()
                        setOnCompletionListener { onPlaybackFinished(packet) }
                        start()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@ChatActivity, "خطأ في تشغيل الصوت: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** الحذف التلقائي: يُطبَّق على الرسائل الواردة فقط بعد انتهاء الاستماع. */
    private fun onPlaybackFinished(packet: WaveMessagePacket) {
        val mine = packet.senderName.equals(senderName, ignoreCase = true)
        if (autoDelete && !mine) deleteMessage(packet.messageId, manual = false)
    }

    private fun deleteMessage(id: String, manual: Boolean) {
        ChatStore.remove(id)
        adapter.removeById(id)
        if (!manual) {
            Toast.makeText(this, "تم حذف الرسالة تلقائياً بعد الاستماع", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportWaveToWav(packet: WaveMessagePacket) {
        lifecycleScope.launch(Dispatchers.IO) {
            val outDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
            val wavFile = AcousticWaveTranslator.reconstructAudioFile(packet, outDir)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ChatActivity, "تم حفظ الملف الصوتي: ${wavFile.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

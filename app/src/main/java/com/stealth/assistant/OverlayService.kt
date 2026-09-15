package com.stealth.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var audioManager: AudioManager

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    private var audioRecord: AudioRecord? = null
    private var isRecordingRaw = false
    private var isAutoStreaming = false

    private var resumeText: String = ""
    private var customContext: String = ""

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSizeLevel = 1

    private val wsUrl = "wss://stealth-mobile.onrender.com"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            resumeText = it.getStringExtra("EXTRA_RESUME_TEXT") ?: ""
            customContext = it.getStringExtra("EXTRA_CUSTOM_CONTEXT") ?: ""
            sendContextToServer()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioRouting()
        startForegroundServiceNotification()
        initOverlayView()
        connectWebSocket()
    }

    private fun setupAudioRouting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } catch (e: Exception) {
            android.util.Log.e("StealthAudio", "Routing setup error: ${e.message}")
        }
    }

    private fun resetAudioRouting() {
        try {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            android.util.Log.e("StealthAudio", "Routing reset error: ${e.message}")
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "stealth_assistant_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Stealth Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Stealth Copilot Active")
            .setContentText("Overlay running on top of screen")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        windowManager.addView(overlayView, params)

        val expandedWindow = overlayView.findViewById<View>(R.id.expandedWindow)
        val minimizedBubble = overlayView.findViewById<View>(R.id.minimizedBubble)
        val dragHeader = overlayView.findViewById<View>(R.id.dragHeader)
        val btnClose = overlayView.findViewById<TextView>(R.id.btnClose)
        val btnMinimize = overlayView.findViewById<TextView>(R.id.btnMinimize)
        val btnResize = overlayView.findViewById<TextView>(R.id.btnResize)
        val btnAuto = overlayView.findViewById<Button>(R.id.btnAutoCapture)
        val btnManual = overlayView.findViewById<Button>(R.id.btnManualCapture)

        btnClose.setOnClickListener { stopSelf() }

        btnMinimize.setOnClickListener {
            expandedWindow.visibility = View.GONE
            minimizedBubble.visibility = View.VISIBLE
        }

        btnResize.setOnClickListener {
            currentSizeLevel = (currentSizeLevel + 1) % 3
            val layoutParams = expandedWindow.layoutParams
            when (currentSizeLevel) {
                0 -> {
                    layoutParams.width = (280 * resources.displayMetrics.density).toInt()
                    layoutParams.height = (200 * resources.displayMetrics.density).toInt()
                }
                1 -> {
                    layoutParams.width = (330 * resources.displayMetrics.density).toInt()
                    layoutParams.height = (290 * resources.displayMetrics.density).toInt()
                }
                2 -> {
                    layoutParams.width = (370 * resources.displayMetrics.density).toInt()
                    layoutParams.height = (400 * resources.displayMetrics.density).toInt()
                }
            }
            expandedWindow.layoutParams = layoutParams
        }

        btnAuto.setOnClickListener {
            if (isAutoStreaming) {
                isAutoStreaming = false
                btnAuto.text = "Auto Stream"
                btnAuto.setBackgroundColor(Color.parseColor("#1E293B"))
                updateStatus("🟢 Ready")
            } else {
                if (isRecordingRaw) stopHardwareRecordingAndSend()
                isAutoStreaming = true
                btnAuto.text = "Streaming..."
                btnAuto.setBackgroundColor(Color.parseColor("#15803D"))
                startAutoAudioLoop()
            }
        }

        btnManual.setOnClickListener {
            if (isRecordingRaw) {
                stopHardwareRecordingAndSend()
                btnManual.text = "Tap to Record"
                btnManual.setBackgroundColor(Color.parseColor("#0284C7"))
            } else {
                if (isAutoStreaming) {
                    isAutoStreaming = false
                    btnAuto.text = "Auto Stream"
                    btnAuto.setBackgroundColor(Color.parseColor("#1E293B"))
                }
                startHardwareRecording()
                btnManual.text = "Stop & Answer"
                btnManual.setBackgroundColor(Color.parseColor("#DC2626"))
            }
        }

        setupDragTouchListener(dragHeader, isBubble = false)
        setupDragTouchListener(minimizedBubble, isBubble = true)
    }

    private fun setupDragTouchListener(targetView: View, isBubble: Boolean) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        targetView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = (event.rawX - initialTouchX).toInt()
                    val diffY = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10) {
                        isClick = false
                        params.x = initialX + diffX
                        params.y = initialY + diffY
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick && isBubble) {
                        overlayView.findViewById<View>(R.id.minimizedBubble).visibility = View.GONE
                        overlayView.findViewById<View>(R.id.expandedWindow).visibility = View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateStatus("🟢 Connected")
                sendContextToServer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateStatus("🔴 Reconnecting...")
                serviceScope.launch {
                    delay(4000)
                    if (serviceScope.isActive) {
                        connectWebSocket()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateStatus("⚪ Disconnected")
            }
        })
    }

    private fun sendContextToServer() {
        if (resumeText.isNotEmpty() || customContext.isNotEmpty()) {
            val payload = JSONObject().apply {
                put("type", "update-context")
                put("resumeText", resumeText)
                put("customContext", customContext)
            }
            webSocket?.send(payload.toString())
        }
    }

    private fun updateStatus(status: String) {
        CoroutineScope(Dispatchers.Main).launch {
            overlayView.findViewById<TextView>(R.id.tvStatus)?.text = status
        }
    }

    private fun handleServerMessage(jsonString: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val json = JSONObject(jsonString)
                val tvQuestion = overlayView.findViewById<TextView>(R.id.tvQuestion)
                val tvAnswer = overlayView.findViewById<TextView>(R.id.tvAnswer)
                val scroll = overlayView.findViewById<ScrollView>(R.id.scrollAnswer)

                when (json.optString("type")) {
                    "question" -> {
                        tvQuestion?.text = "🎙️ ${json.getString("text")}"
                        tvAnswer?.text = ""
                    }
                    "stream-token" -> {
                        tvAnswer?.append(json.getString("text"))
                        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                    "stream-end" -> {
                        updateStatus("⚡ Answered (${json.optString("duration")})")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var rawAudioStream: ByteArrayOutputStream? = null

    @SuppressLint("MissingPermission")
    private fun startHardwareRecording() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            audioRecord?.startRecording()
            isRecordingRaw = true
            rawAudioStream = ByteArrayOutputStream()
            updateStatus("🔴 Recording...")

            serviceScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBufferSize)
                while (isRecordingRaw) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        rawAudioStream?.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("StealthAudio", "Record init error: ${e.message}")
            stopHardwareRecordingAndSend()
        }
    }

    private fun stopHardwareRecordingAndSend() {
        isRecordingRaw = false
        updateStatus("⚡ Processing...")
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
        } finally {
            audioRecord = null
        }

        val rawBytes = rawAudioStream?.toByteArray() ?: ByteArray(0)
        rawAudioStream = null

        if (rawBytes.isNotEmpty()) {
            val wavBytes = addWavHeader(rawBytes, 16000, 1, 16)
            sendAudioBytes(wavBytes)
        }
    }

    private fun startAutoAudioLoop() {
        serviceScope.launch {
            while (isActive && isAutoStreaming) {
                startHardwareRecording()
                delay(4000)
                stopHardwareRecordingAndSend()
                delay(800)
            }
        }
    }

    private fun sendAudioBytes(bytes: ByteArray) {
        try {
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("type", "process-audio")
                put("data", base64Data)
                put("format", "wav")
            }
            webSocket?.send(payload.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen)

        val wavData = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavData, 0, 44)
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.size)
        return wavData
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoStreaming = false
        isRecordingRaw = false
        serviceScope.cancel()
        resetAudioRouting()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        webSocket?.close(1000, "Service destroyed")
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
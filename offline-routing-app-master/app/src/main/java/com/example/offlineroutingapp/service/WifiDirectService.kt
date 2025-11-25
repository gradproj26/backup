package com.example.offlineroutingapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.offlineroutingapp.MainActivity
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class PendingMessage(
    val type: String,
    val data: Any,
    var retryCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

class WifiDirectService : Service() {
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var serverSocket: ServerSocket? = null
    var clientSocket: Socket? = null

    @Volatile var isConnected = false
    private val isConnecting = AtomicBoolean(false)
    private val isServerRunning = AtomicBoolean(false)

    var currentChatId: String? = null
    var isChatActivityVisible = false
    var visibleChatId: String? = null

    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    private var retryJob: Job? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null

    private val maxRetries = 5
    private val retryDelayMs = 3000L
    private val connectionTimeout = 30000 // 30 seconds
    private val socketTimeout = 10000 // 10 seconds

    var onMessageReceived: ((String, Boolean, String?) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onDeliveryStatusChanged: ((String, Boolean) -> Unit)? = null
    var onSeenStatusChanged: ((String, Boolean) -> Unit)? = null
    var onProfileReceived: ((String, String, String) -> Unit)? = null
    var onPairingRequest: ((String, String) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "WifiDirectServiceChannel"
        const val MESSAGE_CHANNEL_ID = "MessageNotificationChannel"
        const val NOTIFICATION_ID = 1
        const val SERVER_PORT = 8888
        private const val TAG = "WifiDirectService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): WifiDirectService = this@WifiDirectService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        try {
            createNotificationChannels()
            startForeground(NOTIFICATION_ID, createNotification("WiFi Direct Service Ready"))
            Log.d(TAG, "Service created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "WiFi Direct Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps WiFi Direct connection alive"
                }

                val messageChannel = NotificationChannel(
                    MESSAGE_CHANNEL_ID,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "New message notifications"
                    enableVibration(true)
                }

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(serviceChannel)
                manager.createNotificationChannel(messageChannel)
                Log.d(TAG, "Notification channels created")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating channels: ${e.message}", e)
            }
        }
    }

    private fun createNotification(contentText: String): Notification {
        return try {
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Offline Chat")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification: ${e.message}", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Offline Chat")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
    }

    private fun showMessageNotification(messageText: String, chatId: String?) {
        try {
            if (isChatActivityVisible && visibleChatId == chatId) {
                Log.d(TAG, "Chat visible, skipping notification")
                return
            }

            val notificationIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_CHAT_ID", chatId)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
                .setContentTitle("New Message")
                .setContentText(messageText)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification: ${e.message}", e)
        }
    }

    fun updateNotification(text: String) {
        try {
            val notification = createNotification(text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}", e)
        }
    }

    /**
     * NETWORK FIX 1: Enhanced Server Start with proper socket configuration
     */
    fun startServer() {
        if (isServerRunning.getAndSet(true)) {
            Log.w(TAG, "Server already running")
            return
        }

        serverJob?.cancel()
        serverJob = serviceScope.launch(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 3

            while (retryCount < maxRetries && isActive) {
                try {
                    Log.d(TAG, "=== STARTING SERVER (Attempt ${retryCount + 1}) ===")

                    // Close any existing socket
                    try {
                        serverSocket?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing old socket: ${e.message}")
                    }

                    // Create server socket with proper configuration
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        soTimeout = connectionTimeout
                        bind(InetSocketAddress(SERVER_PORT))
                    }

                    Log.d(TAG, "Server socket bound to port $SERVER_PORT")
                    withContext(Dispatchers.Main) {
                        updateNotification("Waiting for connection...")
                    }

                    // Accept connection with timeout
                    val socket = withTimeoutOrNull(connectionTimeout.toLong()) {
                        withContext(Dispatchers.IO) {
                            serverSocket?.accept()
                        }
                    }

                    if (socket != null) {
                        Log.d(TAG, "✓ Client connected from: ${socket.inetAddress}")

                        // Configure socket
                        socket.apply {
                            tcpNoDelay = true
                            keepAlive = true
                            soTimeout = socketTimeout
                        }

                        clientSocket = socket
                        isConnected = true
                        isConnecting.set(false)

                        withContext(Dispatchers.Main) {
                            onConnectionStatusChanged?.invoke(true)
                            updateNotification("Connected - Chat active")
                        }

                        // Wait for pairing and profile exchange
                        delay(2000)

                        startRetryMechanism()
                        processPendingMessages()
                        listenForMessages(socket)
                        return@launch // Success - exit retry loop
                    } else {
                        Log.w(TAG, "Connection timeout")
                        retryCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Server error: ${e.message}", e)
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(2000) // Wait before retry
                    }
                }
            }

            // All retries failed
            isServerRunning.set(false)
            withContext(Dispatchers.Main) {
                updateNotification("Server failed after $maxRetries attempts")
                onConnectionStatusChanged?.invoke(false)
            }
        }
    }

    /**
     * NETWORK FIX 2: Enhanced Client Connect with exponential backoff
     */
    fun connectToServer(hostAddress: String) {
        if (isConnecting.getAndSet(true)) {
            Log.w(TAG, "Already connecting")
            return
        }

        clientJob?.cancel()
        clientJob = serviceScope.launch(Dispatchers.IO) {
            var attempts = 0
            val maxAttempts = 10
            var delay = 1000L

            while (attempts < maxAttempts && isActive) {
                try {
                    Log.d(TAG, "=== CLIENT CONNECTING (Attempt ${attempts + 1}/$maxAttempts) ===")

                    withContext(Dispatchers.Main) {
                        updateNotification("Connecting (${attempts + 1}/$maxAttempts)...")
                    }

                    // Create socket with timeout
                    val socket = withContext(Dispatchers.IO) {
                        Socket().apply {
                            reuseAddress = true
                            tcpNoDelay = true
                            keepAlive = true
                            soTimeout = socketTimeout

                            // Connect with explicit timeout
                            connect(InetSocketAddress(hostAddress, SERVER_PORT), socketTimeout)
                        }
                    }

                    if (socket.isConnected) {
                        Log.d(TAG, "✓ Connected to server: $hostAddress:$SERVER_PORT")

                        clientSocket = socket
                        isConnected = true
                        isConnecting.set(false)

                        withContext(Dispatchers.Main) {
                            onConnectionStatusChanged?.invoke(true)
                            updateNotification("Connected - Chat active")
                        }

                        startRetryMechanism()
                        processPendingMessages()
                        listenForMessages(socket)
                        return@launch // Success
                    }

                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Connection timeout on attempt ${attempts + 1}")
                } catch (e: Exception) {
                    Log.w(TAG, "Connection failed: ${e.message}")
                }

                attempts++
                if (attempts < maxAttempts) {
                    Log.d(TAG, "Waiting ${delay}ms before retry...")
                    delay(delay)
                    delay = minOf(delay * 2, 8000L) // Exponential backoff, max 8s
                }
            }

            // All attempts failed
            isConnecting.set(false)
            Log.e(TAG, "Failed to connect after $maxAttempts attempts")
            withContext(Dispatchers.Main) {
                updateNotification("Connection failed")
                onConnectionStatusChanged?.invoke(false)
            }
        }
    }

    private fun startRetryMechanism() {
        retryJob?.cancel()
        retryJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                delay(retryDelayMs)
                try {
                    retryPendingMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in retry: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun processPendingMessages() {
        Log.d(TAG, "Processing ${pendingMessages.size} pending messages")
        retryPendingMessages()
    }

    private suspend fun retryPendingMessages() {
        val iterator = pendingMessages.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()

            if (message.retryCount >= maxRetries) {
                Log.w(TAG, "Message dropped after max retries")
                iterator.remove()
                continue
            }

            if (sendPendingMessage(message)) {
                Log.d(TAG, "✓ Retry successful")
                iterator.remove()
            } else {
                message.retryCount++
                Log.w(TAG, "Retry ${message.retryCount}/$maxRetries")
            }
        }
    }

    private suspend fun sendPendingMessage(message: PendingMessage): Boolean {
        return try {
            when (message.type) {
                "TEXT" -> sendMessageDirect(message.data as? String ?: return false)
                "IMAGE" -> sendImageDirect(message.data as? ByteArray ?: return false)
                "PROFILE" -> {
                    val data = message.data
                    if (data is Triple<*, *, *>) {
                        val userId = data.first as? String ?: return false
                        val name = data.second as? String ?: return false
                        val photo = data.third as? String
                        sendProfileDirect(userId, name, photo)
                    } else false
                }
                "PAIRING_REQUEST" -> {
                    val data = message.data
                    if (data is Pair<*, *>) {
                        val name = data.first as? String ?: return false
                        val addr = data.second as? String ?: return false
                        sendPairingRequestDirect(name, addr)
                    } else false
                }
                "PAIRING_RESPONSE" -> sendPairingResponseDirect(message.data as? Boolean ?: false)
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retry send error: ${e.message}", e)
            false
        }
    }

    /**
     * NETWORK FIX 3: Enhanced message listener with proper error handling
     */
    private suspend fun listenForMessages(socket: Socket) {
        withContext(Dispatchers.IO) {
            var dataInputStream: DataInputStream? = null
            try {
                dataInputStream = DataInputStream(socket.getInputStream())
                Log.d(TAG, "Started listening for messages...")

                while (isConnected && !socket.isClosed && isActive) {
                    try {
                        // Check if data is available
                        if (socket.isClosed || !socket.isConnected) {
                            Log.w(TAG, "Socket closed, stopping listener")
                            break
                        }

                        val messageType = dataInputStream.readUTF()
                        Log.d(TAG, "← Received: $messageType")

                        when (messageType) {
                            "TEXT" -> handleTextMessage(dataInputStream)
                            "IMAGE" -> handleImageMessage(dataInputStream)
                            "DELIVERY_RECEIPT" -> handleDeliveryReceipt(dataInputStream)
                            "SEEN_RECEIPT" -> handleSeenReceipt(dataInputStream)
                            "PROFILE_INFO" -> handleProfileInfo(dataInputStream)
                            "PAIRING_REQUEST" -> handlePairingRequest(dataInputStream)
                            "PAIRING_RESPONSE" -> handlePairingResponse(dataInputStream)
                            else -> Log.w(TAG, "Unknown message type: $messageType")
                        }
                    } catch (e: SocketTimeoutException) {
                        // Timeout is normal, continue listening
                        continue
                    } catch (e: java.io.EOFException) {
                        Log.w(TAG, "Connection closed by peer")
                        break
                    } catch (e: Exception) {
                        if (isConnected) {
                            Log.e(TAG, "Error reading message: ${e.message}", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: ${e.message}", e)
            } finally {
                Log.d(TAG, "Stopped listening")
                isConnected = false
                try {
                    dataInputStream?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing input stream: ${e.message}")
                }
                withContext(Dispatchers.Main) {
                    try {
                        onConnectionStatusChanged?.invoke(false)
                        updateNotification("Disconnected")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in callback: ${e.message}", e)
                    }
                }
            }
        }
    }

    private suspend fun handleTextMessage(input: DataInputStream) {
        try {
            val text = input.readUTF()
            Log.d(TAG, "Text message: ${text.take(50)}...")
            showMessageNotification(text, currentChatId)
            withContext(Dispatchers.Main) {
                try {
                    onMessageReceived?.invoke(text, false, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in text callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling text: ${e.message}", e)
        }
    }

    private suspend fun handleImageMessage(input: DataInputStream) {
        try {
            val size = input.readInt()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            showMessageNotification("📷 Image", currentChatId)
            withContext(Dispatchers.Main) {
                try {
                    onMessageReceived?.invoke("", true, base64)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in image callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling image: ${e.message}", e)
        }
    }

    private suspend fun handleDeliveryReceipt(input: DataInputStream) {
        try {
            val msgId = input.readUTF()
            withContext(Dispatchers.Main) {
                try {
                    onDeliveryStatusChanged?.invoke(msgId, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delivery callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling receipt: ${e.message}", e)
        }
    }

    private suspend fun handleSeenReceipt(input: DataInputStream) {
        try {
            val msgId = input.readUTF()
            withContext(Dispatchers.Main) {
                try {
                    onSeenStatusChanged?.invoke(msgId, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in seen callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling seen: ${e.message}", e)
        }
    }

    private suspend fun handleProfileInfo(input: DataInputStream) {
        try {
            val userId = input.readUTF()
            val name = input.readUTF()
            val photo = input.readUTF()
            Log.d(TAG, "✓ Received profile: $name")
            withContext(Dispatchers.Main) {
                try {
                    onProfileReceived?.invoke(userId, name, photo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in profile callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling profile: ${e.message}", e)
        }
    }

    private suspend fun handlePairingRequest(input: DataInputStream) {
        try {
            val name = input.readUTF()
            val addr = input.readUTF()
            Log.d(TAG, "✓ Pairing request from: $name")
            withContext(Dispatchers.Main) {
                try {
                    onPairingRequest?.invoke(name, addr)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in pairing callback: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling pairing: ${e.message}", e)
        }
    }

    private suspend fun handlePairingResponse(input: DataInputStream) {
        try {
            val accepted = input.readBoolean()
            Log.d(TAG, "Pairing: ${if (accepted) "ACCEPTED" else "DECLINED"}")
            if (!accepted) {
                closeConnections()
                withContext(Dispatchers.Main) {
                    try {
                        onConnectionStatusChanged?.invoke(false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in response callback: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling response: ${e.message}", e)
        }
    }

    fun sendMessage(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!sendMessageDirect(text)) {
                    pendingMessages.add(PendingMessage("TEXT", text))
                    Log.d(TAG, "Message queued for retry")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
            }
        }
    }

    private suspend fun sendMessageDirect(text: String): Boolean {
        return try {
            val socket = clientSocket ?: return false
            if (isConnected && !socket.isClosed) {
                withContext(Dispatchers.IO) {
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeUTF("TEXT")
                    out.writeUTF(text)
                    out.flush()
                }
                Log.d(TAG, "✓ Sent text message")
                true
            } else {
                Log.w(TAG, "Cannot send: not connected")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Send error: ${e.message}", e)
            isConnected = false
            withContext(Dispatchers.Main) {
                try {
                    onConnectionStatusChanged?.invoke(false)
                } catch (ex: Exception) {
                    Log.e(TAG, "Callback error: ${ex.message}", ex)
                }
            }
            false
        }
    }

    fun sendImage(imageBytes: ByteArray) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!sendImageDirect(imageBytes)) {
                    pendingMessages.add(PendingMessage("IMAGE", imageBytes))
                    Log.d(TAG, "Image queued for retry")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending image: ${e.message}", e)
            }
        }
    }

    private suspend fun sendImageDirect(imageBytes: ByteArray): Boolean {
        return try {
            val socket = clientSocket ?: return false
            if (isConnected && !socket.isClosed) {
                withContext(Dispatchers.IO) {
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeUTF("IMAGE")
                    out.writeInt(imageBytes.size)
                    out.write(imageBytes)
                    out.flush()
                }
                Log.d(TAG, "✓ Sent image")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Image error: ${e.message}", e)
            isConnected = false
            false
        }
    }

    fun sendProfileInfo(userId: String, displayName: String, photoBase64: String?) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!sendProfileDirect(userId, displayName, photoBase64)) {
                    pendingMessages.add(PendingMessage("PROFILE", Triple(userId, displayName, photoBase64)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending profile: ${e.message}", e)
            }
        }
    }

    private suspend fun sendProfileDirect(userId: String, displayName: String, photoBase64: String?): Boolean {
        return try {
            val socket = clientSocket ?: return false
            if (isConnected && !socket.isClosed) {
                withContext(Dispatchers.IO) {
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeUTF("PROFILE_INFO")
                    out.writeUTF(userId)
                    out.writeUTF(displayName)
                    out.writeUTF(photoBase64 ?: "")
                    out.flush()
                }
                Log.d(TAG, "✓ Sent profile")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Profile error: ${e.message}", e)
            false
        }
    }

    fun sendPairingRequest(deviceName: String, deviceAddress: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!sendPairingRequestDirect(deviceName, deviceAddress)) {
                    pendingMessages.add(PendingMessage("PAIRING_REQUEST", Pair(deviceName, deviceAddress)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending pairing request: ${e.message}", e)
            }
        }
    }

    private suspend fun sendPairingRequestDirect(deviceName: String, deviceAddress: String): Boolean {
        return try {
            val socket = clientSocket ?: return false
            if (isConnected && !socket.isClosed) {
                withContext(Dispatchers.IO) {
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeUTF("PAIRING_REQUEST")
                    out.writeUTF(deviceName)
                    out.writeUTF(deviceAddress)
                    out.flush()
                }
                Log.d(TAG, "✓ Sent pairing request")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Pairing request error: ${e.message}", e)
            false
        }
    }

    fun sendPairingResponse(accepted: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!sendPairingResponseDirect(accepted)) {
                    pendingMessages.add(PendingMessage("PAIRING_RESPONSE", accepted))
                } else if (!accepted) {
                    delay(500)
                    closeConnections()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending pairing response: ${e.message}", e)
            }
        }
    }

    private suspend fun sendPairingResponseDirect(accepted: Boolean): Boolean {
        return try {
            val socket = clientSocket ?: return false
            if (isConnected && !socket.isClosed) {
                withContext(Dispatchers.IO) {
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeUTF("PAIRING_RESPONSE")
                    out.writeBoolean(accepted)
                    out.flush()
                }
                Log.d(TAG, "✓ Sent pairing response")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Response error: ${e.message}", e)
            false
        }
    }

    fun sendDeliveryReceipt(messageId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = clientSocket ?: return@launch
                if (isConnected && !socket.isClosed) {
                    withContext(Dispatchers.IO) {
                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeUTF("DELIVERY_RECEIPT")
                        out.writeUTF(messageId)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receipt error: ${e.message}", e)
            }
        }
    }

    fun sendSeenReceipt(messageId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = clientSocket ?: return@launch
                if (isConnected && !socket.isClosed) {
                    withContext(Dispatchers.IO) {
                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeUTF("SEEN_RECEIPT")
                        out.writeUTF(messageId)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Seen error: ${e.message}", e)
            }
        }
    }

    /**
     * NETWORK FIX 4: Proper connection cleanup
     */
    fun closeConnections() {
        Log.d(TAG, "=== CLOSING CONNECTIONS ===")
        isConnected = false
        isServerRunning.set(false)
        isConnecting.set(false)

        try {
            // Cancel all jobs
            retryJob?.cancel()
            serverJob?.cancel()
            clientJob?.cancel()

            // Clear pending messages
            pendingMessages.clear()

            // Close sockets with proper error handling
            try {
                clientSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket: ${e.message}")
            }

            try {
                serverSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket: ${e.message}")
            }

            clientSocket = null
            serverSocket = null

            Log.d(TAG, "✓ All connections closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in closeConnections: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        try {
            closeConnections()
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
    }
}
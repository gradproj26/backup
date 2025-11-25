package com.example.offlineroutingapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.offlineroutingapp.MainActivity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*

class WifiDirectService : Service() {
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var serverSocket: ServerSocket? = null
    var clientSocket: Socket? = null
    var isConnected = false
    var currentChatId: String? = null
    var onMessageReceived: ((String, Boolean, String?) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onDeliveryStatusChanged: ((String, Boolean) -> Unit)? = null
    var onSeenStatusChanged: ((String, Boolean) -> Unit)? = null
    var onProfileReceived: ((String, String, String) -> Unit)? = null
    var isChatActivityVisible = false
    var visibleChatId: String? = null

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
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createNotification("WiFi Direct Service Running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline Chat")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showMessageNotification(messageText: String, chatId: String?) {
        // Don't show notification if the chat is currently open
        if (isChatActivityVisible && visibleChatId == chatId) {
            android.util.Log.d(TAG, "Chat is visible, skipping notification")
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
    }
    fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                android.util.Log.d(TAG, "Server started on port $SERVER_PORT")
                updateNotification("Waiting for connection...")

                val socket = serverSocket?.accept()
                clientSocket = socket
                isConnected = true

                android.util.Log.d(TAG, "Client connected!")

                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(true)
                    updateNotification("Connected - Chat active")
                }

                listenForMessages(socket!!)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Server error: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateNotification("Connection failed")
                }
            }
        }
    }

    fun connectToServer(hostAddress: String) {
        serviceScope.launch {
            try {
                android.util.Log.d(TAG, "Attempting to connect to $hostAddress:$SERVER_PORT")
                val socket = Socket(hostAddress, SERVER_PORT)
                clientSocket = socket
                isConnected = true

                android.util.Log.d(TAG, "Connected to server!")

                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(true)
                    updateNotification("Connected - Chat active")
                }

                listenForMessages(socket)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Client connection error: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateNotification("Connection failed: ${e.message}")
                }
            }
        }
    }

    private fun listenForMessages(socket: Socket) {
        serviceScope.launch {
            try {
                val dataInputStream = DataInputStream(socket.getInputStream())
                while (isConnected && !socket.isClosed) {
                    try {
                        val messageType = dataInputStream.readUTF()
                        android.util.Log.d(TAG, "Received message type: $messageType")

                        when (messageType) {
                            "TEXT" -> {
                                val textMessage = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Received text: $textMessage")

                                showMessageNotification(textMessage, currentChatId)

                                withContext(Dispatchers.Main) {
                                    onMessageReceived?.invoke(textMessage, false, null)
                                }

                                sendDeliveryReceipt("msg_${System.currentTimeMillis()}")
                            }
                            "IMAGE" -> {
                                val imageSize = dataInputStream.readInt()
                                val imageBytes = ByteArray(imageSize)
                                dataInputStream.readFully(imageBytes)

                                val base64Image = android.util.Base64.encodeToString(
                                    imageBytes,
                                    android.util.Base64.NO_WRAP
                                )

                                showMessageNotification("📷 Image", currentChatId)

                                withContext(Dispatchers.Main) {
                                    onMessageReceived?.invoke("", true, base64Image)
                                }

                                sendDeliveryReceipt("img_${System.currentTimeMillis()}")
                            }
                            "DELIVERY_RECEIPT" -> {
                                val messageId = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Message delivered: $messageId")
                                withContext(Dispatchers.Main) {
                                    onDeliveryStatusChanged?.invoke(messageId, true)
                                }
                            }
                            "SEEN_RECEIPT" -> {
                                val messageId = dataInputStream.readUTF()
                                android.util.Log.d(TAG, "Message seen: $messageId")
                                withContext(Dispatchers.Main) {
                                    onSeenStatusChanged?.invoke(messageId, true)
                                }
                            }
                            "PROFILE_INFO" -> {
                                val userId = dataInputStream.readUTF()
                                val displayName = dataInputStream.readUTF()
                                val photoBase64 = dataInputStream.readUTF()

                                android.util.Log.d(TAG, "Received profile: $userId, $displayName")

                                withContext(Dispatchers.Main) {
                                    onProfileReceived?.invoke(userId, displayName, photoBase64)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isConnected) {
                            android.util.Log.e(TAG, "Error reading message: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error in message listener: ${e.message}")
            } finally {
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                    updateNotification("Disconnected")
                }
            }
        }
    }

    fun sendMessage(text: String) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    android.util.Log.d(TAG, "Sending text message: $text")
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("TEXT")
                    dataOutputStream.writeUTF(text)
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Message sent successfully")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending message: ${e.message}")
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                }
            }
        }
    }

    fun sendImage(imageBytes: ByteArray) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    android.util.Log.d(TAG, "Sending image, size: ${imageBytes.size}")
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("IMAGE")
                    dataOutputStream.writeInt(imageBytes.size)
                    dataOutputStream.write(imageBytes)
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Image sent successfully")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending image: ${e.message}")
                isConnected = false
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                }
            }
        }
    }

    fun sendDeliveryReceipt(messageId: String) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("DELIVERY_RECEIPT")
                    dataOutputStream.writeUTF(messageId)
                    dataOutputStream.flush()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending delivery receipt: ${e.message}")
            }
        }
    }

    fun sendSeenReceipt(messageId: String) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("SEEN_RECEIPT")
                    dataOutputStream.writeUTF(messageId)
                    dataOutputStream.flush()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending seen receipt: ${e.message}")
            }
        }
    }

    fun sendProfileInfo(userId: String, displayName: String, photoBase64: String?) {
        serviceScope.launch {
            try {
                if (clientSocket != null && isConnected) {
                    val dataOutputStream = DataOutputStream(clientSocket!!.getOutputStream())
                    dataOutputStream.writeUTF("PROFILE_INFO")
                    dataOutputStream.writeUTF(userId)
                    dataOutputStream.writeUTF(displayName)
                    dataOutputStream.writeUTF(photoBase64 ?: "")
                    dataOutputStream.flush()
                    android.util.Log.d(TAG, "Profile info sent")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending profile info: ${e.message}")
            }
        }
    }

    fun closeConnections() {
        isConnected = false
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error closing connections: ${e.message}")
        }
        clientSocket = null
        serverSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnections()
        serviceScope.cancel()
    }
}
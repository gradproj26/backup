package com.example.offlineroutingapp

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.util.Base64
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.service.WifiDirectService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

class ChatActivity : AppCompatActivity() {
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var imageBtn: ImageButton
    private lateinit var backBtn: ImageButton
    private lateinit var chatUserName: TextView
    private lateinit var chatUserProfile: ImageView

    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()

    private val database by lazy { AppDatabase.getDatabase(this) }
    private var chatId: String? = null
    private var wifiService: WifiDirectService? = null
    private var serviceBound = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sendImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiDirectService.LocalBinder
            wifiService = binder.getService()
            serviceBound = true

            wifiService?.isChatActivityVisible = true
            wifiService?.visibleChatId = chatId

            wifiService?.onMessageReceived = { text, isImage, imageData ->
                handleReceivedMessage(text, isImage, imageData)
            }

            wifiService?.onConnectionStatusChanged = { connected ->
                runOnUiThread {
                    // Update UI based on connection status
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiService?.isChatActivityVisible = false
            wifiService?.visibleChatId = null
            wifiService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatId = intent.getStringExtra("CHAT_ID")
        val userName = intent.getStringExtra("USER_NAME") ?: "Unknown"
        val userPhoto = intent.getStringExtra("USER_PHOTO")

        initializeViews()
        setupUI(userName, userPhoto)
        setupRecyclerView()
        loadMessages()
        setupListeners()
        bindToService()
    }

    private fun bindToService() {
        val serviceIntent = Intent(this, WifiDirectService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun initializeViews() {
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendBtn = findViewById(R.id.sendBtn)
        imageBtn = findViewById(R.id.imageBtn)
        backBtn = findViewById(R.id.backBtn)
        chatUserName = findViewById(R.id.chatUserName)
        chatUserProfile = findViewById(R.id.chatUserProfile)
    }

    private fun setupUI(userName: String, userPhoto: String?) {
        chatUserName.text = userName

        if (!userPhoto.isNullOrEmpty() && File(userPhoto).exists()) {
            val bitmap = BitmapFactory.decodeFile(userPhoto)
            chatUserProfile.setImageBitmap(bitmap)
        } else {
            chatUserProfile.setImageResource(android.R.drawable.ic_menu_camera)
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            messages = messages,
            onRetryClick = { message ->
                retryMessage(message)
            }
        )
        messagesRecyclerView.adapter = messageAdapter
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun loadMessages() {
        chatId?.let { id ->
            lifecycleScope.launch {
                database.messageDao().getMessagesByChatId(id).collect { messageEntities ->
                    messages.clear()
                    messages.addAll(messageEntities.map { entity ->
                        Message(
                            id = entity.id,
                            text = entity.text,
                            isSentByMe = entity.isSentByMe,
                            timestamp = entity.timestamp,
                            isImage = entity.isImage,
                            imageData = entity.imageData,
                            isDelivered = entity.isDelivered,
                            isSeen = entity.isSeen,
                            isFailed = !entity.isDelivered && entity.isSentByMe &&
                                    (System.currentTimeMillis() - entity.timestamp > 30000)
                        )
                    })
                    messageAdapter.notifyDataSetChanged()
                    if (messages.isNotEmpty()) {
                        messagesRecyclerView.scrollToPosition(messages.size - 1)
                    }
                }

                database.chatDao().markChatAsRead(id)
            }
        }
    }

    private fun setupListeners() {
        backBtn.setOnClickListener {
            finish()
        }

        sendBtn.setOnClickListener {
            sendMessage()
        }

        imageBtn.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun sendMessage() {
        val messageText = messageInput.text.toString().trim()
        if (messageText.isEmpty()) return

        if (wifiService?.isConnected == true) {
            wifiService?.sendMessage(messageText)

            chatId?.let { id ->
                lifecycleScope.launch {
                    val message = MessageEntity(
                        chatId = id,
                        text = messageText,
                        isSentByMe = true,
                        isDelivered = false
                    )
                    database.messageDao().insertMessage(message)

                    val chat = database.chatDao().getChatById(id)
                    chat?.let {
                        val updatedChat = it.copy(
                            lastMessage = messageText,
                            lastMessageTime = System.currentTimeMillis()
                        )
                        database.chatDao().updateChat(updatedChat)
                    }
                }
            }

            messageInput.text.clear()
        } else {
            Toast.makeText(
                this,
                "Not connected. Message saved for retry.",
                Toast.LENGTH_SHORT
            ).show()

            chatId?.let { id ->
                lifecycleScope.launch {
                    val message = MessageEntity(
                        chatId = id,
                        text = messageText,
                        isSentByMe = true,
                        isDelivered = false
                    )
                    database.messageDao().insertMessage(message)
                }
            }
            messageInput.text.clear()
        }
    }

    private fun sendImage(uri: Uri) {
        if (wifiService?.isConnected != true) {
            Toast.makeText(this, "Not connected. Cannot send images.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val compressedBitmap = compressImage(bitmap)
                val byteArrayOutputStream = ByteArrayOutputStream()
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                wifiService?.sendImage(imageBytes)

                chatId?.let { id ->
                    val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    val message = MessageEntity(
                        chatId = id,
                        text = "",
                        isSentByMe = true,
                        isImage = true,
                        imageData = base64Image,
                        isDelivered = false
                    )
                    database.messageDao().insertMessage(message)

                    val chat = database.chatDao().getChatById(id)
                    chat?.let {
                        val updatedChat = it.copy(
                            lastMessage = "📷 Image",
                            lastMessageTime = System.currentTimeMillis()
                        )
                        database.chatDao().updateChat(updatedChat)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Failed to send image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun compressImage(bitmap: Bitmap): Bitmap {
        val maxWidth = 800
        val maxHeight = 600
        val scaleFactor = minOf(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height
        )
        return if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scaleFactor).toInt(),
                (bitmap.height * scaleFactor).toInt(),
                true
            )
        } else bitmap
    }

    private fun retryMessage(message: Message) {
        if (wifiService?.isConnected != true) {
            Toast.makeText(this, "Cannot retry: Not connected", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Retry Message")
            .setMessage("Do you want to resend this message?")
            .setPositiveButton("Retry") { _, _ ->
                if (message.isImage && message.imageData != null) {
                    val imageBytes = Base64.decode(message.imageData, Base64.NO_WRAP)
                    wifiService?.sendImage(imageBytes)
                } else {
                    wifiService?.sendMessage(message.text)
                }

                lifecycleScope.launch {
                    chatId?.let { id ->
                        val messages = database.messageDao().getMessagesByChatId(id).first()
                        val dbMessage = messages.find { it.id == message.id }
                        dbMessage?.let {
                            database.messageDao().updateMessage(it.copy(isDelivered = true))
                        }
                    }
                }

                Toast.makeText(this, "Message resent", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleReceivedMessage(text: String, isImage: Boolean, imageData: String?) {
        chatId?.let { id ->
            lifecycleScope.launch {
                val message = MessageEntity(
                    chatId = id,
                    text = text,
                    isSentByMe = false,
                    isImage = isImage,
                    imageData = imageData,
                    isDelivered = true
                )
                database.messageDao().insertMessage(message)

                val chat = database.chatDao().getChatById(id)
                chat?.let {
                    val updatedChat = it.copy(
                        lastMessage = if (isImage) "📷 Image" else text,
                        lastMessageTime = System.currentTimeMillis()
                    )
                    database.chatDao().updateChat(updatedChat)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wifiService?.isChatActivityVisible = true
        wifiService?.visibleChatId = chatId
    }

    override fun onPause() {
        super.onPause()
        wifiService?.isChatActivityVisible = false
        wifiService?.visibleChatId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            wifiService?.isChatActivityVisible = false
            wifiService?.visibleChatId = null
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
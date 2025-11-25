package com.example.offlineroutingapp

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.service.WifiDirectService
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

            wifiService?.onMessageReceived = { text, isImage, imageData ->
                handleReceivedMessage(text, isImage, imageData)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
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
        messageAdapter = MessageAdapter(messages)
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
                            text = entity.text,
                            isSentByMe = entity.isSentByMe,
                            timestamp = entity.timestamp,
                            isImage = entity.isImage,
                            imageData = entity.imageData
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
        if (messageText.isNotEmpty()) {
            if (wifiService?.isConnected == true) {
                wifiService?.sendMessage(messageText)

                chatId?.let { id ->
                    lifecycleScope.launch {
                        val message = MessageEntity(
                            chatId = id,
                            text = messageText,
                            isSentByMe = true
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
                Toast.makeText(this, "Not connected. Go to Discover tab to connect.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendImage(uri: Uri) {
        if (wifiService?.isConnected == true) {
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
                        val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                        val message = MessageEntity(
                            chatId = id,
                            text = "",
                            isSentByMe = true,
                            isImage = true,
                            imageData = base64Image
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
        } else {
            Toast.makeText(this, "Not connected. Go to Discover tab to connect.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun compressImage(bitmap: Bitmap): Bitmap {
        val maxWidth = 800
        val maxHeight = 600
        val scaleFactor = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        return if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scaleFactor).toInt(),
                (bitmap.height * scaleFactor).toInt(),
                true
            )
        } else bitmap
    }

    private fun handleReceivedMessage(text: String, isImage: Boolean, imageData: String?) {
        chatId?.let { id ->
            lifecycleScope.launch {
                val message = MessageEntity(
                    chatId = id,
                    text = text,
                    isSentByMe = false,
                    isImage = isImage,
                    imageData = imageData
                )
                database.messageDao().insertMessage(message)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
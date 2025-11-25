package com.example.offlineroutingapp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.offlineroutingapp.adapters.ViewPagerAdapter
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.fragments.DiscoverFragment
import com.example.offlineroutingapp.service.WifiDirectService
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import com.example.offlineroutingapp.nativebridge.MasaarBridge


class MainActivity : AppCompatActivity() {
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var intentFilter: IntentFilter

    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var peersAdapter: ArrayAdapter<String>

    private var wifiService: WifiDirectService? = null
    private var serviceBound = false
    private var currentChatId: String? = null
    private var isGroupOwner = false

    private val database by lazy { AppDatabase.getDatabase(this) }

    private val reqNearbyPermissionCode = 1001
    private val reqStoragePermissionCode = 1002

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

            Log.d(TAG, "Service connected")

            wifiService?.onMessageReceived = { text, isImage, imageData ->
                handleReceivedMessage(text, isImage, imageData)
            }

            wifiService?.onConnectionStatusChanged = { connected ->
                Log.d(TAG, "Connection status changed: $connected")
                if (!connected) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_SHORT).show()
                    }
                    currentChatId = null
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Connected successfully!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            wifiService?.onProfileReceived = { userId, displayName, photoBase64 ->
                handleReceivedProfile(userId, displayName, photoBase64)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiService = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_tabbed)

        Log.d(TAG, "MainActivity created")

        initializeViews()
        setupWifiP2p()
        setupViewPager()
        startAndBindService()

        handleNotificationIntent(intent)

        // Check WiFi state
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Please enable WiFi for device discovery", Toast.LENGTH_LONG).show()
        }
        val msg = MasaarBridge.buildMessage("Hello from app!", "user_b1234567")
        println(msg)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent) {
        val chatId = intent.getStringExtra("OPEN_CHAT_ID")
        if (chatId != null) {
            Log.d(TAG, "Opening chat from notification: $chatId")
            lifecycleScope.launch {
                val chat = database.chatDao().getChatById(chatId)
                chat?.let {
                    val chatIntent = Intent(this@MainActivity, ChatActivity::class.java).apply {
                        putExtra("CHAT_ID", it.chatId)
                        putExtra("USER_NAME", it.userName)
                        putExtra("USER_PHOTO", it.userProfilePhoto)
                    }
                    startActivity(chatIntent)
                }
            }
        }
    }

    private fun initializeViews() {
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
    }

    private fun setupViewPager() {
        viewPagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = viewPagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Chats"
                1 -> "Discover"
                2 -> "Profile"
                else -> ""
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position == 1) {
                    setupDiscoverFragment()
                }
            }
        })
    }

    private fun setupDiscoverFragment() {
        val fragment = supportFragmentManager.findFragmentByTag("f1") as? DiscoverFragment
        fragment?.let {
            val discoverBtn = it.getDiscoverButton()
            val peersList = it.getPeersList()

            peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
            peersList.adapter = peersAdapter

            discoverBtn.setOnClickListener {
                startDiscovery()
            }

            peersList.setOnItemClickListener { _, _, position, _ ->
                if (position < peers.size) {
                    connectToPeer(peers[position])
                }
            }
        }
    }

    private fun setupWifiP2p() {
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        Log.d(TAG, "WiFi P2P initialized")

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, WifiDirectService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Service started and binding...")
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers.clear()
        peers.addAll(peerList.deviceList)

        Log.d(TAG, "Peers found: ${peers.size}")

        if (peers.isEmpty()) {
            peersAdapter.clear()
            peersAdapter.add(getString(R.string.no_devices_found))
            return@PeerListListener
        }

        val names = peers.map { it.deviceName }
        peersAdapter.clear()
        peersAdapter.addAll(names)

        peers.forEach { peer ->
            Log.d(TAG, "Peer: ${peer.deviceName} - ${peer.deviceAddress}")
        }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        val groupOwnerAddress = info.groupOwnerAddress
        isGroupOwner = info.isGroupOwner

        Log.d(TAG, "=== CONNECTION ESTABLISHED ===")
        Log.d(TAG, "IsGroupOwner: $isGroupOwner")
        Log.d(TAG, "GroupOwner IP: ${groupOwnerAddress?.hostAddress}")
        Log.d(TAG, "Current Chat ID: $currentChatId")

        if (isGroupOwner) {
            Log.d(TAG, "Starting server as Group Owner...")
            wifiService?.startServer()

            lifecycleScope.launch {
                kotlinx.coroutines.delay(2000)
                exchangeProfileInfo()
            }
        } else {
            Log.d(TAG, "Connecting to server at ${groupOwnerAddress.hostAddress}")
            wifiService?.connectToServer(groupOwnerAddress.hostAddress)

            lifecycleScope.launch {
                kotlinx.coroutines.delay(3000)
                exchangeProfileInfo()
            }
        }

        wifiService?.currentChatId = currentChatId

        viewPager.currentItem = 0
        Toast.makeText(this, "Connection established!", Toast.LENGTH_SHORT).show()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WiFi P2P state changed. Enabled: $isEnabled")

                    if (!isEnabled) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "WiFi Direct is disabled. Please enable WiFi.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Peers changed, requesting peer list...")
                    if (hasNearbyPermission()) {
                        try {
                            manager.requestPeers(channel, peerListListener)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException on requestPeers", e)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    Log.d(TAG, "Connection changed. Connected: ${networkInfo?.isConnected}")

                    if (networkInfo?.isConnected == true) {
                        if (hasNearbyPermission()) {
                            try {
                                manager.requestConnectionInfo(channel, connectionInfoListener)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException on requestConnectionInfo", e)
                            }
                        }
                    } else {
                        Log.d(TAG, "Disconnected from WiFi P2P")
                        wifiService?.closeConnections()
                        currentChatId = null
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d(TAG, "This device changed: ${device?.deviceName}")
                }
            }
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        Log.d(TAG, "Attempting to connect to: ${device.deviceName} (${device.deviceAddress})")

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        currentChatId = device.deviceAddress

        if (hasNearbyPermission()) {
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Connection initiated successfully")
                        Toast.makeText(this@MainActivity, "Connecting to ${device.deviceName}...", Toast.LENGTH_SHORT).show()

                        lifecycleScope.launch {
                            val existingChat = database.chatDao().getChatById(device.deviceAddress)
                            if (existingChat == null) {
                                val newChat = ChatEntity(
                                    chatId = device.deviceAddress,
                                    userName = "Connecting...",
                                    userProfilePhoto = null
                                )
                                database.chatDao().insertChat(newChat)
                                Log.d(TAG, "Created placeholder chat")
                            } else {
                                Log.d(TAG, "Chat already exists")
                            }
                        }
                    }

                    override fun onFailure(reason: Int) {
                        val reasonText = when(reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
                            WifiP2pManager.ERROR -> "Error"
                            WifiP2pManager.BUSY -> "Busy"
                            else -> "Unknown ($reason)"
                        }
                        Log.e(TAG, "Connection failed: $reasonText")
                        Toast.makeText(this@MainActivity, "Connection failed: $reasonText", Toast.LENGTH_LONG).show()
                        currentChatId = null
                    }
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on connect", e)
                Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w(TAG, "Missing nearby device permission")
            requestNearbyPermission()
        }
    }

    fun reconnectToDevice(deviceAddress: String) {
        Log.d(TAG, "Reconnect requested for: $deviceAddress")

        if (wifiService?.isConnected == true && currentChatId == deviceAddress) {
            Toast.makeText(this, "Already connected!", Toast.LENGTH_SHORT).show()
            return
        }

        val device = peers.find { it.deviceAddress == deviceAddress }

        if (device != null) {
            Log.d(TAG, "Device found in peer list, connecting...")
            connectToPeer(device)
        } else {
            Log.w(TAG, "Device not in peer list. Starting discovery...")
            Toast.makeText(this, "Searching for device...", Toast.LENGTH_SHORT).show()

            viewPager.currentItem = 1

            lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                startDiscovery()
            }
        }
    }

    private fun exchangeProfileInfo() {
        lifecycleScope.launch {
            val user = database.userDao().getUser()
            user?.let {
                var photoBase64: String? = null
                if (!it.profilePhotoPath.isNullOrEmpty()) {
                    try {
                        val file = File(it.profilePhotoPath)
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            photoBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            Log.d(TAG, "Profile photo encoded, size: ${bytes.size}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error encoding profile photo: ${e.message}")
                    }
                }

                wifiService?.sendProfileInfo(it.userId, it.displayName, photoBase64)
                Log.d(TAG, "Sent profile info: ${it.userId}, ${it.displayName}")
            }
        }
    }

    private fun handleReceivedProfile(userId: String, displayName: String, photoBase64: String) {
        lifecycleScope.launch {
            Log.d(TAG, "Handling received profile: $userId, $displayName, hasPhoto: ${photoBase64.isNotEmpty()}")

            currentChatId?.let { chatId ->
                var photoPath: String? = null
                if (photoBase64.isNotEmpty()) {
                    try {
                        val photoBytes = android.util.Base64.decode(photoBase64, android.util.Base64.NO_WRAP)
                        val profileDir = File(filesDir, "received_profiles")
                        if (!profileDir.exists()) {
                            profileDir.mkdirs()
                        }

                        val fileName = "profile_${userId}_${System.currentTimeMillis()}.jpg"
                        val file = File(profileDir, fileName)
                        file.writeBytes(photoBytes)
                        photoPath = file.absolutePath

                        Log.d(TAG, "Saved received profile photo to: $photoPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving received photo: ${e.message}", e)
                    }
                }

                val chat = database.chatDao().getChatById(chatId)
                if (chat != null) {
                    val updatedChat = chat.copy(
                        userName = displayName,
                        userProfilePhoto = photoPath
                    )
                    database.chatDao().updateChat(updatedChat)
                    Log.d(TAG, "Updated existing chat with profile: $displayName")
                } else {
                    val newChat = ChatEntity(
                        chatId = chatId,
                        userName = displayName,
                        userProfilePhoto = photoPath
                    )
                    database.chatDao().insertChat(newChat)
                    Log.d(TAG, "Created new chat with profile: $displayName")
                }
            }
        }
    }

    private fun handleReceivedMessage(text: String, isImage: Boolean, imageData: String?) {
        Log.d(TAG, "Message received. Text: ${if(isImage) "[Image]" else text}")
        currentChatId?.let { chatId ->
            lifecycleScope.launch {
                val message = MessageEntity(
                    chatId = chatId,
                    text = text,
                    isSentByMe = false,
                    isImage = isImage,
                    imageData = imageData,
                    isDelivered = true
                )
                database.messageDao().insertMessage(message)

                val chat = database.chatDao().getChatById(chatId)
                chat?.let {
                    val updatedChat = it.copy(
                        lastMessage = if (isImage) "📷 Image" else text,
                        lastMessageTime = System.currentTimeMillis(),
                        unreadCount = it.unreadCount + 1
                    )
                    database.chatDao().updateChat(updatedChat)
                }
            }
        }
    }

    private fun sendImage(imageUri: Uri) {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val compressedBitmap = compressImage(bitmap)
                val byteArrayOutputStream = ByteArrayOutputStream()
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                wifiService?.sendImage(imageBytes)

                currentChatId?.let { chatId ->
                    val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    val message = MessageEntity(
                        chatId = chatId,
                        text = "",
                        isSentByMe = true,
                        isImage = true,
                        imageData = base64Image,
                        isDelivered = false
                    )
                    database.messageDao().insertMessage(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending image: ${e.message}")
            }
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

    private fun startDiscovery() {
        if (!hasNearbyPermission()) {
            Log.w(TAG, "Missing permission for discovery")
            requestNearbyPermission()
            return
        }

        Log.d(TAG, "Starting peer discovery...")

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (!wifiManager.isWifiEnabled) {
                Toast.makeText(this, "Please enable WiFi first", Toast.LENGTH_LONG).show()
                Log.e(TAG, "WiFi is disabled")
                return
            }

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing ACCESS_FINE_LOCATION permission")
                requestNearbyPermission()
                return
            }

            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery started successfully")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.search_started),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(reason: Int) {
                    val errorMsg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported on this device"
                        WifiP2pManager.BUSY -> "System busy, please try again"
                        WifiP2pManager.ERROR -> "Internal error occurred"
                        else -> "Failed with code: $reason"
                    }
                    Log.e(TAG, "Discovery failed: $errorMsg")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Discovery failed: $errorMsg",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in startDiscovery: ${e.message}", e)
            Toast.makeText(this, "Error starting discovery: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNearbyPermission() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissionsToRequest, reqNearbyPermissionCode)
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), reqStoragePermissionCode)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
        Log.d(TAG, "Receiver registered")
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
        Log.d(TAG, "Receiver unregistered")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        Log.d(TAG, "MainActivity destroyed")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            reqNearbyPermissionCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Nearby permission granted")
                    startDiscovery()
                } else {
                    Log.w(TAG, "Nearby permission denied")
                    Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                }
            }
            reqStoragePermissionCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Storage permission granted")
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
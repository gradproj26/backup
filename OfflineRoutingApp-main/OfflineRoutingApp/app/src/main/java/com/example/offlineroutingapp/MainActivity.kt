package com.example.offlineroutingapp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.offlineroutingapp.adapters.ViewPagerAdapter
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.fragments.DiscoverFragment
import com.example.offlineroutingapp.service.WifiDirectService
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var intentFilter: IntentFilter

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val peers = mutableListOf<WifiP2pDevice>()
    private val reqNearbyPermissionCode = 1001
    private var isGroupOwner = false
    private var currentChatId: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    private var wifiService: WifiDirectService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiDirectService.LocalBinder
            wifiService = binder.getService()
            serviceBound = true

            wifiService?.onConnectionStatusChanged = { connected ->
                runOnUiThread {
                    if (connected) {
                        Toast.makeText(this@MainActivity, "Connected", Toast.LENGTH_SHORT).show()
                        viewPager.currentItem = 0
                    } else {
                        Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            wifiService?.onProfileReceived = { userId, displayName, photoBase64 ->
                handleReceivedProfile(userId, displayName, photoBase64)
            }

            wifiService?.onPairingRequest = { deviceName, deviceAddress ->
                showPairingDialog(deviceName, deviceAddress)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiService = null
            serviceBound = false
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        Log.d(TAG, "Peer list received with ${peerList.deviceList.size} devices")

        peers.clear()
        peers.addAll(peerList.deviceList)

        peerList.deviceList.forEach { device ->
            Log.d(TAG, "Found peer: ${device.deviceName} (${device.deviceAddress})")
        }

        val discoverFragment = supportFragmentManager.fragments
            .find { it is DiscoverFragment } as? DiscoverFragment

        if (discoverFragment != null) {
            Log.d(TAG, "Updating DiscoverFragment with peers")
            discoverFragment.updatePeersList(peers)
        } else {
            Log.w(TAG, "DiscoverFragment not found")
        }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        val groupOwnerAddress = info.groupOwnerAddress
        isGroupOwner = info.isGroupOwner

        if (!info.groupFormed) {
            Log.w(TAG, "Group not formed yet")
            return@ConnectionInfoListener
        }

        Log.d(TAG, "=== GROUP FORMED ===")
        Log.d(TAG, "Group Owner: $isGroupOwner")
        Log.d(TAG, "Group Owner Address: ${groupOwnerAddress?.hostAddress}")

        lifecycleScope.launch(Dispatchers.IO) {
            if (isGroupOwner) {
                // NETWORK FIX: Group Owner waits slightly before starting server
                Log.d(TAG, "→ I am GROUP OWNER - Waiting 2s before starting server")
                delay(2000)
                wifiService?.startServer()
            } else {
                // NETWORK FIX: Client waits longer for server to be ready
                Log.d(TAG, "→ I am CLIENT - Waiting 5s for server to start")
                delay(5000)

                groupOwnerAddress?.hostAddress?.let { host ->
                    Log.d(TAG, "Attempting connection to $host")
                    wifiService?.connectToServer(host)
                }

                // NETWORK FIX: Wait for connection before sending pairing
                delay(3000)

                val user = database.userDao().getUser()
                val myDeviceName = user?.displayName ?: Build.MODEL
                currentChatId?.let {
                    Log.d(TAG, "Sending pairing request as $myDeviceName")
                    wifiService?.sendPairingRequest(myDeviceName, it)
                }
            }
        }
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
                            Toast.makeText(
                                this@MainActivity,
                                "WiFi Direct is disabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Peers list changed")
                    if (hasNearbyPermission()) {
                        try {
                            manager.requestPeers(channel, peerListListener)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException on requestPeers", e)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "Connection state changed")
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        Log.d(TAG, "Connected to peer")
                        if (hasNearbyPermission()) {
                            try {
                                manager.requestConnectionInfo(channel, connectionInfoListener)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException on requestConnectionInfo", e)
                            }
                        }
                    } else {
                        Log.d(TAG, "Disconnected from peer")
                        wifiService?.closeConnections()
                        currentChatId = null
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<WifiP2pDevice>(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                    )
                    Log.d(TAG, "This device changed: ${device?.deviceName}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_tabbed)

        setupWifiP2p()
        setupTabs()
        startAndBindService()

        intent.getStringExtra("OPEN_CHAT_ID")?.let { chatId ->
            openChat(chatId)
        }
    }

    private fun setupWifiP2p() {
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun setupTabs() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = true
        viewPager.offscreenPageLimit = 3

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Chats"
                1 -> "Discover"
                2 -> "Profile"
                else -> ""
            }
        }.attach()
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, WifiDirectService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun startDiscovery() {
        Log.d(TAG, "startDiscovery called")

        if (!hasNearbyPermission()) {
            Log.w(TAG, "Missing nearby permission")
            Toast.makeText(this, "Please grant location/nearby devices permission", Toast.LENGTH_LONG).show()
            requestNearbyPermission()
            return
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Please enable WiFi", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Stopping previous discovery")
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Previous discovery stopped successfully")
                startPeerDiscovery()
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to stop previous discovery: $reason")
                startPeerDiscovery()
            }
        })
    }

    private fun startPeerDiscovery() {
        Log.d(TAG, "startPeerDiscovery called")

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "ACCESS_FINE_LOCATION not granted")
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            requestNearbyPermission()
            return
        }

        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery started successfully")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Searching for devices...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Discovery failed with reason: $reason")
                    val errorMsg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported"
                        WifiP2pManager.BUSY -> "System busy, try again"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Discovery failed: $reason"
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            errorMsg,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception during discovery: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }

        currentChatId = device.deviceAddress
        wifiService?.currentChatId = device.deviceAddress

        if (hasNearbyPermission()) {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { performConnection(device, config) }
                override fun onFailure(reason: Int) { performConnection(device, config) }
            })
        } else {
            requestNearbyPermission()
        }
    }

    private fun performConnection(device: WifiP2pDevice, config: WifiP2pConfig) {
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated to ${device.deviceName}")
                    Toast.makeText(
                        this@MainActivity,
                        "Connecting to ${device.deviceName}...",
                        Toast.LENGTH_SHORT
                    ).show()

                    lifecycleScope.launch(Dispatchers.IO) {
                        val existingChat = database.chatDao().getChatById(device.deviceAddress)
                        if (existingChat == null) {
                            Log.d(TAG, "Creating initial chat entry for ${device.deviceAddress}")
                            database.chatDao().insertChat(
                                ChatEntity(
                                    chatId = device.deviceAddress,
                                    userName = device.deviceName,
                                    userProfilePhoto = null,
                                    lastMessage = "Connecting...",
                                    lastMessageTime = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Connection failed with reason: $reason")
                    Toast.makeText(
                        this@MainActivity,
                        "Connection failed: $reason",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on connect", e)
        }
    }

    fun reconnectToDevice(deviceAddress: String) {
        if (wifiService?.isConnected == true && currentChatId == deviceAddress) {
            Toast.makeText(this, "Already connected!", Toast.LENGTH_SHORT).show()
            return
        }

        val device = peers.find { it.deviceAddress == deviceAddress }

        if (device != null) {
            Toast.makeText(
                this,
                "Reconnecting to ${device.deviceName}...",
                Toast.LENGTH_SHORT
            ).show()
            connectToPeer(device)
        } else {
            Toast.makeText(
                this,
                "Device not found. Starting discovery...",
                Toast.LENGTH_LONG
            ).show()
            viewPager.currentItem = 1
            startDiscovery()
        }
    }

    private fun showPairingDialog(deviceName: String, deviceAddress: String) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Pairing Request")
                .setMessage("$deviceName wants to connect with you")
                .setPositiveButton("Accept") { _, _ ->
                    lifecycleScope.launch {
                        wifiService?.sendPairingResponse(true)
                        delay(1000)
                        exchangeProfileInfo()
                    }
                }
                .setNegativeButton("Decline") { _, _ ->
                    lifecycleScope.launch {
                        wifiService?.sendPairingResponse(false)
                        disconnect()
                    }
                }
                .setCancelable(false)
                .show()
        }
    }

    private suspend fun exchangeProfileInfo() {
        val user = database.userDao().getUser()
        user?.let {
            var photoBase64: String? = null

            if (!it.profilePhotoPath.isNullOrEmpty()) {
                val file = File(it.profilePhotoPath)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    photoBase64 = android.util.Base64.encodeToString(
                        bytes,
                        android.util.Base64.NO_WRAP
                    )
                }
            }

            wifiService?.sendProfileInfo(it.userId, it.displayName, photoBase64)
        }
    }

    private fun handleReceivedProfile(userId: String, displayName: String, photoBase64: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            currentChatId?.let { chatId ->
                var photoPath: String? = null

                if (photoBase64.isNotEmpty()) {
                    val profileDir = File(filesDir, "received_profiles")
                    if (!profileDir.exists()) {
                        profileDir.mkdirs()
                    }

                    val photoFile = File(profileDir, "profile_$userId.jpg")
                    val bytes = android.util.Base64.decode(photoBase64, android.util.Base64.NO_WRAP)
                    photoFile.writeBytes(bytes)
                    photoPath = photoFile.absolutePath
                }

                val updatedChat = ChatEntity(
                    chatId = chatId,
                    userName = displayName,
                    userProfilePhoto = photoPath,
                    lastMessage = "",
                    lastMessageTime = System.currentTimeMillis()
                )

                val existingChat = database.chatDao().getChatById(chatId)
                if (existingChat != null) {
                    Log.d(TAG, "Updating existing chat: $chatId")
                    database.chatDao().updateChat(updatedChat)
                } else {
                    Log.d(TAG, "Inserting new chat: $chatId")
                    database.chatDao().insertChat(updatedChat)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "✓ Connected to $displayName",
                        Toast.LENGTH_SHORT
                    ).show()
                    viewPager.currentItem = 0
                }
            }
        }
    }

    fun disconnect() {
        if (hasNearbyPermission()) {
            try {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Toast.makeText(
                            this@MainActivity,
                            "Disconnected",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Disconnect failed: $reason")
                    }
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on disconnect", e)
            }
        }

        wifiService?.closeConnections()
        currentChatId = null
    }

    private fun openChat(chatId: String) {
        lifecycleScope.launch {
            val chat = database.chatDao().getChatById(chatId)
            if (chat != null) {
                val intent = Intent(this@MainActivity, ChatActivity::class.java).apply {
                    putExtra("CHAT_ID", chat.chatId)
                    putExtra("USER_NAME", chat.userName)
                    putExtra("USER_PHOTO", chat.userProfilePhoto)
                }
                startActivity(intent)
            }
        }
    }

    private fun hasNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNearbyPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, reqNearbyPermissionCode)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        try {
            registerReceiver(receiver, intentFilter)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Receiver already registered", e)
        }

        if (!hasNearbyPermission()) {
            Log.w(TAG, "Missing permissions on resume")
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi is disabled")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")

        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Receiver not registered", e)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")

        try {
            // Unbind service first
            if (serviceBound) {
                wifiService?.isChatActivityVisible = false
                wifiService?.visibleChatId = null
                unbindService(serviceConnection)
                serviceBound = false
            }

            // Clean up WiFi P2P resources
            try {
                manager.stopPeerDiscovery(channel, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery: ${e.message}")
            }

            // Unregister receiver if still registered
            try {
                unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqNearbyPermissionCode) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
                startDiscovery()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
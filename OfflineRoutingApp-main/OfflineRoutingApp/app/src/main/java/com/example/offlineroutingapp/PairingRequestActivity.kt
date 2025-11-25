package com.example.offlineroutingapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.offlineroutingapp.data.AppDatabase
import kotlinx.coroutines.launch

class PairingRequestActivity : AppCompatActivity() {
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_request)

        database = AppDatabase.getDatabase(this)

        val deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unknown Device"
        val deviceAddress = intent.getStringExtra("DEVICE_ADDRESS") ?: ""

        findViewById<TextView>(R.id.pairingMessage).text =
            "$deviceName wants to connect with you"

        findViewById<Button>(R.id.acceptBtn).setOnClickListener {
            acceptPairing(deviceAddress, deviceName)
        }

        findViewById<Button>(R.id.declineBtn).setOnClickListener {
            declinePairing()
        }
    }

    private fun acceptPairing(deviceAddress: String, deviceName: String) {
        lifecycleScope.launch {
            // Send acceptance to MainActivity
            val intent = Intent().apply {
                putExtra("PAIRING_ACCEPTED", true)
                putExtra("DEVICE_ADDRESS", deviceAddress)
                putExtra("DEVICE_NAME", deviceName)
            }
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun declinePairing() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
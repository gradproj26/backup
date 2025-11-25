package com.example.offlineroutingapp.fragments

import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.fragment.app.Fragment
import com.example.offlineroutingapp.MainActivity
import com.example.offlineroutingapp.R

class DiscoverFragment : Fragment() {
    private lateinit var discoverBtn: Button
    private lateinit var peersList: ListView
    private lateinit var peersAdapter: ArrayAdapter<String>
    private val peers = mutableListOf<WifiP2pDevice>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_discover, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        discoverBtn = view.findViewById(R.id.discoverBtn)
        peersList = view.findViewById(R.id.peersList)

        peersAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )
        peersList.adapter = peersAdapter

        discoverBtn.setOnClickListener {
            (activity as? MainActivity)?.startDiscovery()
        }

        peersList.setOnItemClickListener { _, _, position, _ ->
            if (position < peers.size) {
                (activity as? MainActivity)?.connectToPeer(peers[position])
            }
        }
    }

    fun updatePeersList(newPeers: List<WifiP2pDevice>) {
        peers.clear()
        peers.addAll(newPeers)

        if (peers.isEmpty()) {
            peersAdapter.clear()
            peersAdapter.add("No devices found")
            return
        }

        val names = peers.map { "${it.deviceName} (${getStatusString(it.status)})" }
        peersAdapter.clear()
        peersAdapter.addAll(names)
    }

    private fun getStatusString(status: Int): String {
        return when (status) {
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }
}
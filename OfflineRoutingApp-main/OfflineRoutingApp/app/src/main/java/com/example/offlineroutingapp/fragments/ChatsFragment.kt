package com.example.offlineroutingapp.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.ChatActivity
import com.example.offlineroutingapp.MainActivity
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.adapters.ChatListAdapter
import com.example.offlineroutingapp.data.AppDatabase
import kotlinx.coroutines.launch

class ChatsFragment : Fragment() {
    private lateinit var chatsRecyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var chatListAdapter: ChatListAdapter
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    companion object {
        private const val TAG = "ChatsFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chats, container, false)
    }

    // ... (imports and class definition are correct)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // CORRECTED: Removed the extra 's' from 'chats'
        chatsRecyclerView = view.findViewById(R.id.chatsRecyclerView)
        emptyStateText = view.findViewById(R.id.emptyStateText)

        setupRecyclerView()
        loadChats()
    }



    override fun onResume() {
        super.onResume()
        // Reload chats when fragment becomes visible
        loadChats()
    }

    private fun setupRecyclerView() {
        chatListAdapter = ChatListAdapter(
            onChatClick = { chatEntity ->
                Log.d(TAG, "Chat clicked: ${chatEntity.chatId}")
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("CHAT_ID", chatEntity.chatId)
                    putExtra("USER_NAME", chatEntity.userName)
                    putExtra("USER_PHOTO", chatEntity.userProfilePhoto)
                }
                startActivity(intent)
            },
            onReconnectClick = { chatEntity ->
                Log.d(TAG, "Reconnect clicked: ${chatEntity.chatId}")
                // Trigger reconnection through MainActivity
                (activity as? MainActivity)?.reconnectToDevice(chatEntity.chatId)
            }
        )
        chatsRecyclerView.adapter = chatListAdapter
        chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun loadChats() {
        lifecycleScope.launch {
            try {
                database.chatDao().getAllChats().collect { chats ->
                    Log.d(TAG, "Loaded ${chats.size} chats")

                    if (chats.isEmpty()) {
                        chatsRecyclerView.visibility = View.GONE
                        emptyStateText.visibility = View.VISIBLE
                    } else {
                        chatsRecyclerView.visibility = View.VISIBLE
                        emptyStateText.visibility = View.GONE
                        chatListAdapter.submitList(chats)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading chats: ${e.message}", e)
            }
        }
    }
}
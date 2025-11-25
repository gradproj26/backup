package com.example.offlineroutingapp.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
    private lateinit var chatListAdapter: ChatListAdapter
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatsRecyclerView = view.findViewById(R.id.chatsRecyclerView)
        setupRecyclerView()
        loadChats()
    }

    private fun setupRecyclerView() {
        chatListAdapter = ChatListAdapter(
            onChatClick = { chatEntity ->
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("CHAT_ID", chatEntity.chatId)
                    putExtra("USER_NAME", chatEntity.userName)
                    putExtra("USER_PHOTO", chatEntity.userProfilePhoto)
                }
                startActivity(intent)
            },
            onReconnectClick = { chatEntity ->
                // Trigger reconnection through MainActivity
                (activity as? MainActivity)?.reconnectToDevice(chatEntity.chatId)
            }
        )
        chatsRecyclerView.adapter = chatListAdapter
        chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun loadChats() {
        lifecycleScope.launch {
            database.chatDao().getAllChats().collect { chats ->
                chatListAdapter.submitList(chats)
            }
        }
    }
}
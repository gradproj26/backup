package com.example.offlineroutingapp.adapters

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.data.entities.ChatEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ChatListAdapter(
    private val onChatClick: (ChatEntity) -> Unit,
    private val onReconnectClick: (ChatEntity) -> Unit
) : ListAdapter<ChatEntity, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileImage: ImageView = itemView.findViewById(R.id.chatProfileImage)
        val userName: TextView = itemView.findViewById(R.id.chatUserName)
        val lastMessage: TextView = itemView.findViewById(R.id.chatLastMessage)
        val timestamp: TextView = itemView.findViewById(R.id.chatTimestamp)
        val unreadBadge: TextView = itemView.findViewById(R.id.chatUnreadBadge)
        val reconnectBtn: Button = itemView.findViewById(R.id.reconnectBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = getItem(position)

        holder.userName.text = chat.userName
        holder.lastMessage.text = chat.lastMessage.ifEmpty { "No messages yet" }
        holder.timestamp.text = formatTimestamp(chat.lastMessageTime)

        // Load profile photo if available
        if (!chat.userProfilePhoto.isNullOrEmpty() && File(chat.userProfilePhoto).exists()) {
            val bitmap = BitmapFactory.decodeFile(chat.userProfilePhoto)
            holder.profileImage.setImageBitmap(bitmap)
        } else {
            holder.profileImage.setImageResource(android.R.drawable.ic_menu_camera)
        }

        // Show unread badge
        if (chat.unreadCount > 0) {
            holder.unreadBadge.visibility = View.VISIBLE
            holder.unreadBadge.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
        } else {
            holder.unreadBadge.visibility = View.GONE
        }

        // Reconnect button
        holder.reconnectBtn.setOnClickListener {
            onReconnectClick(chat)
        }

        holder.itemView.setOnClickListener {
            onChatClick(chat)
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            diff < 604800000 -> "${diff / 86400000}d ago"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

class ChatDiffCallback : DiffUtil.ItemCallback<ChatEntity>() {
    override fun areItemsTheSame(oldItem: ChatEntity, newItem: ChatEntity): Boolean {
        return oldItem.chatId == newItem.chatId
    }

    override fun areContentsTheSame(oldItem: ChatEntity, newItem: ChatEntity): Boolean {
        return oldItem == newItem
    }
}
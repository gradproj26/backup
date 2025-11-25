package com.example.offlineroutingapp

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.app.AlertDialog
import android.content.Context

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
        val messageImage: ImageView = itemView.findViewById(R.id.messageImage)
        val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        val messageStatus: TextView = itemView.findViewById(R.id.messageStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        if (message.isImage && !message.imageData.isNullOrEmpty()) {
            holder.messageText.visibility = View.GONE
            holder.messageImage.visibility = View.VISIBLE

            try {
                val imageBytes = Base64.decode(message.imageData, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                holder.messageImage.setImageBitmap(bitmap)

                // Make image clickable to view fullscreen
                holder.messageImage.setOnClickListener {
                    showFullscreenImage(holder.itemView.context, imageBytes)
                }
            } catch (e: Exception) {
                android.util.Log.e("MessageAdapter", "Error decoding image: ${e.message}")
                holder.messageImage.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            if (message.isSentByMe) {
                holder.messageContainer.gravity = Gravity.END
                holder.messageImage.setBackgroundResource(R.drawable.bg_message_sent)
            } else {
                holder.messageContainer.gravity = Gravity.START
                holder.messageImage.setBackgroundResource(R.drawable.bg_message_received)
            }
        } else {
            holder.messageText.visibility = View.VISIBLE
            holder.messageImage.visibility = View.GONE
            holder.messageText.text = message.text
            if (message.isSentByMe) {
                holder.messageContainer.gravity = Gravity.END
                holder.messageText.setBackgroundResource(R.drawable.bg_message_sent)
            } else {
                holder.messageContainer.gravity = Gravity.START
                holder.messageText.setBackgroundResource(R.drawable.bg_message_received)
            }
        }

        if (message.isSentByMe) {
            holder.messageStatus.visibility = View.VISIBLE
            holder.messageStatus.text = when {
                message.isSeen -> "✓✓ Seen"
                message.isDelivered -> "✓✓ Delivered"
                else -> "✓ Sent"
            }
        } else {
            holder.messageStatus.visibility = View.GONE
        }
    }

    private fun showFullscreenImage(context: Context, imageBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        AlertDialog.Builder(context)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .create()
            .show()
    }

    override fun getItemCount(): Int = messages.size
}
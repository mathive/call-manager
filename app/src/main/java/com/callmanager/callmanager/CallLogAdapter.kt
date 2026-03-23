package com.callmanager.callmanager

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.*

data class CallLogItem(
    var name: String,
    val number: String,
    val type: String,
    val time: Long,
    val simId: Int = 1,
    val photoUri: String? = null,
    var isBlockedLocally: Boolean = false,
    var dbName: String? = null,
    var isGlobalSpam: Boolean = false,
    var isWhitelisted: Boolean = false
)

class CallLogAdapter(private val callLogs: List<CallLogItem>) :
    RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {

    private val bgColors = arrayOf("#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FF9800", "#FF5722")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvContactName)
        val tvNumber: TextView = view.findViewById(R.id.tvPhoneNumber)
        val tvTime: TextView = view.findViewById(R.id.tvCallTime)
        val tvSimLabel: TextView = view.findViewById(R.id.tvSimLabel)
        val ivCallType: ImageView = view.findViewById(R.id.ivCallType)
        val ivProfileBg: ShapeableImageView = view.findViewById(R.id.ivProfileBg)
        val tvInitials: TextView = view.findViewById(R.id.tvInitials)
        val ivUnknownIcon: ImageView = view.findViewById(R.id.ivUnknownIcon)
        val ivInfo: ImageView = view.findViewById(R.id.ivInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = callLogs[position]
        
        // Prioritize name from database if available
        val displayName = when {
            !item.dbName.isNullOrEmpty() -> item.dbName
            !item.name.isNullOrEmpty() && item.name != item.number -> item.name
            else -> item.number
        }
        
        val isStillUnknown = displayName == item.number
        
        holder.tvName.text = displayName
        holder.tvNumber.text = item.number
        
        // Show number only if the display name is different from the number
        holder.tvNumber.visibility = if (displayName == item.number) View.GONE else View.VISIBLE
        
        when {
            item.isBlockedLocally -> {
                holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_red))
                setWhiteTextUI(holder)
            }
            else -> {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                holder.tvName.setTextColor(Color.BLACK)
                val secondaryColor = ContextCompat.getColor(holder.itemView.context, R.color.text_secondary)
                holder.tvNumber.setTextColor(secondaryColor)
                holder.tvTime.setTextColor(secondaryColor)
                holder.tvSimLabel.setTextColor(secondaryColor)
                holder.ivInfo.imageTintList = ColorStateList.valueOf(secondaryColor)
            }
        }

        // Hide call-specific details if time is 0 (Treat as Contact)
        if (item.time == 0L) {
            holder.tvTime.visibility = View.GONE
            holder.tvSimLabel.visibility = View.GONE
            holder.ivCallType.visibility = View.GONE
        } else {
            holder.tvTime.visibility = View.VISIBLE
            holder.tvSimLabel.visibility = View.VISIBLE
            holder.ivCallType.visibility = View.VISIBLE
            holder.tvTime.text = formatCallTime(item.time)
            holder.tvSimLabel.text = item.simId.toString()
            bindCallDirection(holder, item.type)
        }

        bindProfile(holder, item, isStillUnknown)

        // Open Contact Details on Click
        val clickListener = View.OnClickListener {
            val intent = Intent(holder.itemView.context, ContactDetailsActivity::class.java).apply {
                putExtra("name", displayName)
                putExtra("number", item.number)
                putExtra("photoUri", item.photoUri)
            }
            holder.itemView.context.startActivity(intent)
        }

        holder.itemView.setOnClickListener(clickListener)
        holder.ivInfo.setOnClickListener(clickListener)
    }

    private fun setWhiteTextUI(holder: ViewHolder) {
        holder.tvName.setTextColor(Color.WHITE)
        val lightGray = Color.parseColor("#EEEEEE")
        holder.tvNumber.setTextColor(lightGray)
        holder.tvTime.setTextColor(lightGray)
        holder.tvSimLabel.setTextColor(Color.WHITE)
        holder.ivInfo.imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun bindProfile(holder: ViewHolder, item: CallLogItem, isUnknown: Boolean) {
        Glide.with(holder.itemView.context).clear(holder.ivProfileBg)
        holder.ivProfileBg.setImageDrawable(null)
        holder.ivProfileBg.setBackgroundColor(Color.TRANSPARENT)
        holder.ivProfileBg.strokeWidth = 0f
        holder.tvInitials.visibility = View.GONE
        holder.ivUnknownIcon.visibility = View.GONE

        if (item.isWhitelisted) {
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.verified_green))
            holder.ivProfileBg.strokeColor = ColorStateList.valueOf(Color.WHITE)
            holder.ivProfileBg.strokeWidth = 4f
            holder.ivUnknownIcon.setImageResource(R.drawable.ic_verified)
            holder.ivUnknownIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            holder.ivUnknownIcon.visibility = View.VISIBLE
        } else if (item.isBlockedLocally || item.isGlobalSpam) {
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(Color.parseColor("#E74C3C")) // Red circle
            
            // Add white border only if the row background is also red (Personal Block)
            if (item.isBlockedLocally) {
                holder.ivProfileBg.strokeColor = ColorStateList.valueOf(Color.WHITE)
                holder.ivProfileBg.strokeWidth = 4f
            }

            holder.ivUnknownIcon.setImageResource(R.drawable.ic_block)
            holder.ivUnknownIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            holder.ivUnknownIcon.visibility = View.VISIBLE
            holder.tvInitials.visibility = View.GONE
        } else if (!item.photoUri.isNullOrEmpty()) {
            holder.ivProfileBg.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(item.photoUri)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.ivProfileBg)
        } else if (!isUnknown) {
            val nameToUse = if (!item.dbName.isNullOrEmpty()) item.dbName!! else item.name
            val colorIndex = Math.abs(nameToUse.hashCode() % bgColors.size)
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(Color.parseColor(bgColors[colorIndex]))
            holder.tvInitials.visibility = View.VISIBLE
            holder.tvInitials.text = getInitials(nameToUse)
        } else {
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_black))
            holder.ivUnknownIcon.setImageResource(R.drawable.ic_person)
            holder.ivUnknownIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            holder.ivUnknownIcon.visibility = View.VISIBLE
        }
    }

    private fun bindCallDirection(holder: ViewHolder, type: String) {
        val color = when (type) {
            "Incoming" -> "#27AE60"
            "Outgoing" -> "#2196F3"
            "Missed" -> "#E74C3C"
            else -> "#757575"
        }
        holder.ivCallType.imageTintList = ColorStateList.valueOf(Color.parseColor(color))

        val icon = when (type) {
            "Incoming" -> R.drawable.ic_call_incoming
            "Outgoing" -> R.drawable.ic_call_outgoing
            "Missed" -> R.drawable.ic_call_missed
            else -> R.drawable.ic_call_incoming
        }
        holder.ivCallType.setImageResource(icon)
    }

    private fun formatCallTime(time: Long): String {
        return when {
            DateUtils.isToday(time) -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(time))
            DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(time))
        }
    }

    private fun getInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex())
        return if (parts.size >= 2) {
            (parts[0][0].toString() + parts[1][0].toString()).uppercase()
        } else if (parts.isNotEmpty()) {
            parts[0][0].toString().uppercase()
        } else "?"
    }

    override fun getItemCount() = callLogs.size
}

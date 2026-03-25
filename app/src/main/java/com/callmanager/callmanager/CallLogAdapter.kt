package com.callmanager.callmanager

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogAdapter(callLogs: List<CallLogItem>) :
    RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {

    private val callLogs = callLogs.toMutableList()
    private val bgColors = arrayOf("#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FF9800", "#FF5722")
    private val lookupBlockedOrSpam = setOf("blocked", "spam")
    private val lookupWhiteList = setOf("whitelist")
    private val lookupBlueSources = setOf("verified", "user_contact")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowContainer: ConstraintLayout = view.findViewById(R.id.rowContainer)
        val tvName: TextView = view.findViewById(R.id.tvContactName)
        val tvNumber: TextView = view.findViewById(R.id.tvPhoneNumber)
        val tvTime: TextView = view.findViewById(R.id.tvCallTime)
        val tvSimLabel: TextView = view.findViewById(R.id.tvSimLabel)
        val ivCallType: ImageView = view.findViewById(R.id.ivCallType)
        val ivProfileBg: ShapeableImageView = view.findViewById(R.id.ivProfileBg)
        val tvInitials: TextView = view.findViewById(R.id.tvInitials)
        val ivUnknownIcon: ImageView = view.findViewById(R.id.ivUnknownIcon)
        val progressLookup: ProgressBar = view.findViewById(R.id.progressLookup)
        val ivInfo: ImageView = view.findViewById(R.id.ivInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = callLogs[position]
        val baseDisplayName = item.name.ifBlank { item.number }
        val displayName = if (item.callCount > 1) {
            "$baseDisplayName(${item.callCount})"
        } else {
            baseDisplayName
        }
        val isUnknown = baseDisplayName == item.number

        holder.tvName.text = displayName
        holder.tvNumber.text = item.number
        holder.tvNumber.visibility = if (isUnknown) View.GONE else View.VISIBLE

        val secondaryColor = ContextCompat.getColor(holder.itemView.context, R.color.text_secondary)
        holder.rowContainer.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_white))
        holder.tvName.setTextColor(Color.BLACK)
        holder.tvNumber.setTextColor(secondaryColor)
        holder.tvTime.setTextColor(secondaryColor)
        holder.tvSimLabel.setTextColor(secondaryColor)
        holder.ivInfo.imageTintList = ColorStateList.valueOf(secondaryColor)

        holder.tvTime.text = formatCallTime(item.time)
        holder.tvSimLabel.text = item.simId.toString()
        bindCallDirection(holder, item.type)
        bindProfile(holder, item, isUnknown)

        val openDetails = View.OnClickListener {
            val intent = Intent(holder.itemView.context, ContactDetailsActivity::class.java).apply {
                putExtra("name", baseDisplayName)
                putExtra("number", item.number)
                putExtra("photoUri", item.photoUri)
                putExtra("lookupSource", item.lookupSource)
            }
            holder.itemView.context.startActivity(intent)
        }
        holder.itemView.setOnClickListener(openDetails)
        holder.ivInfo.setOnClickListener(openDetails)
    }

    private fun bindProfile(holder: ViewHolder, item: CallLogItem, isUnknown: Boolean) {
        val context = holder.itemView.context
        Glide.with(holder.itemView.context).clear(holder.ivProfileBg)
        holder.ivProfileBg.setImageDrawable(null)
        holder.ivProfileBg.setBackgroundColor(Color.TRANSPARENT)
        holder.ivProfileBg.strokeWidth = 0f
        holder.tvInitials.visibility = View.GONE
        holder.ivUnknownIcon.visibility = View.GONE
        holder.progressLookup.visibility = View.GONE

        if (!item.photoUri.isNullOrEmpty()) {
            holder.ivProfileBg.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(item.photoUri)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.ivProfileBg)
        } else if (item.lookupSource in lookupBlockedOrSpam) {
            holder.rowContainer.setBackgroundColor(ContextCompat.getColor(context, R.color.blocked_card_bg))
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(ContextCompat.getColor(context, R.color.brand_red))
            holder.ivUnknownIcon.setImageResource(R.drawable.ic_block)
            holder.ivUnknownIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
            holder.ivUnknownIcon.visibility = View.VISIBLE
        } else if (item.lookupSource in lookupWhiteList) {
            holder.rowContainer.setBackgroundColor(ContextCompat.getColor(context, R.color.whitelist_card_bg))
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(ContextCompat.getColor(context, R.color.verified_green))
            holder.tvInitials.visibility = View.VISIBLE
            holder.tvInitials.setTextColor(ContextCompat.getColor(context, R.color.white))
            holder.tvInitials.text = getInitials(item.name)
        } else if (item.lookupSource in lookupBlueSources) {
            holder.rowContainer.setBackgroundColor(ContextCompat.getColor(context, R.color.verified_card_bg))
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(ContextCompat.getColor(context, R.color.outgoing_blue))
            holder.tvInitials.visibility = View.VISIBLE
            holder.tvInitials.setTextColor(ContextCompat.getColor(context, R.color.white))
            holder.tvInitials.text = getInitials(item.name)
        } else if (!isUnknown) {
            val colorIndex = kotlin.math.abs(item.name.hashCode() % bgColors.size)
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(bgColors[colorIndex].toColorInt())
            holder.tvInitials.visibility = View.VISIBLE
            holder.tvInitials.setTextColor(Color.WHITE)
            holder.tvInitials.text = getInitials(item.name)
        } else {
            holder.ivProfileBg.visibility = View.VISIBLE
            holder.ivProfileBg.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_black))
            if (item.isLookupInProgress) {
                holder.progressLookup.visibility = View.VISIBLE
            } else {
                holder.ivUnknownIcon.setImageResource(R.drawable.ic_person)
                holder.ivUnknownIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
                holder.ivUnknownIcon.visibility = View.VISIBLE
            }
        }
    }

    private fun bindCallDirection(holder: ViewHolder, type: String) {
        val color = when (type) {
            "Incoming" -> "#27AE60"
            "Outgoing" -> "#2196F3"
            "Missed" -> "#E74C3C"
            else -> "#757575"
        }
        holder.ivCallType.imageTintList = ColorStateList.valueOf(color.toColorInt())

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
        } else if (parts.isNotEmpty() && parts[0].isNotBlank()) {
            parts[0][0].toString().uppercase()
        } else {
            "?"
        }
    }

    fun updateData(newItems: List<CallLogItem>) {
        val oldItems = callLogs.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return oldItem.number == newItem.number &&
                    oldItem.time == newItem.time &&
                    oldItem.type == newItem.type &&
                    oldItem.callCount == newItem.callCount
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })

        callLogs.clear()
        callLogs.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = callLogs.size
}

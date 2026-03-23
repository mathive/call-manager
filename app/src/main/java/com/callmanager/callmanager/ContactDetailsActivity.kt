package com.callmanager.callmanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ContactDetailsActivity : AppCompatActivity() {

    private val WHATSAPP_PKG = "com.whatsapp"
    private val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var isBlockedLocally = false
    private var isWhitelisted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_details)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra("name") ?: "Unknown"
        val number = intent.getStringExtra("number") ?: ""
        val photoUri = intent.getStringExtra("photoUri")

        val tvName: TextView = findViewById(R.id.tvDetailName)
        val ivProfile: ShapeableImageView = findViewById(R.id.ivDetailProfile)
        val tvInContactsLabel: TextView = findViewById(R.id.tvInContactsLabel)

        tvName.text = name

        checkGlobalAndLocalStatus(number, tvName, ivProfile)
        setupActionButtons(number, name)

        if (!photoUri.isNullOrEmpty()) {
            Glide.with(this)
                .load(photoUri)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(ivProfile)
        } else {
            // Default "Unknown" user UI: Circle with white person icon
            ivProfile.setImageResource(R.drawable.ic_person)
            ivProfile.imageTintList = ColorStateList.valueOf(Color.WHITE)
            ivProfile.setBackgroundColor(getColor(R.color.brand_black))
            ivProfile.setPadding(30, 30, 30, 30)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            checkContactStatus(number, tvInContactsLabel)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadActualCallHistory(number)
        }
    }

    private fun checkGlobalAndLocalStatus(number: String, tvName: TextView, ivProfile: ShapeableImageView) {
        val currentUser = auth.currentUser ?: return
        val cleanNumber = normalizeNumber(number)
        
        lifecycleScope.launch {
            try {
                // Check Personal Block
                val localDoc = db.collection("users").document(currentUser.uid)
                    .collection("blocked_numbers").document(cleanNumber).get().await()
                isBlockedLocally = localDoc.exists()
                
                // Check Whitelist
                val whiteDoc = db.collection("users").document(currentUser.uid)
                    .collection("white_list_numbers").document(cleanNumber).get().await()
                isWhitelisted = whiteDoc.exists()
                
                updateBlockButtonUI()
                updateVerifiedButtonUI()

                // Priority: Whitelist (Green) > Blocked (Red) > Default (Black)
                if (isWhitelisted) {
                    applyVerifiedUI(ivProfile)
                } else if (isBlockedLocally) {
                    applyBlockedUI(ivProfile)
                } else {
                    // Check Global Spam for Name and UI updates
                    val globalDoc = db.collection("global_spam").document(cleanNumber).get().await()
                    if (globalDoc.exists()) {
                        val globalName = globalDoc.getString("primaryName")
                        if (!globalName.isNullOrEmpty()) {
                            tvName.text = globalName
                        }
                        
                        val isVerified = globalDoc.getBoolean("isVerifiedSpam") ?: false
                        if (isVerified) {
                            applyBlockedUI(ivProfile)
                        }
                    }
                }

            } catch (e: Exception) { }
        }
    }

    private fun normalizeNumber(number: String): String {
        val digitsOnly = number.replace("[^0-9]".toRegex(), "")
        return when {
            digitsOnly.length == 10 -> "91$digitsOnly"
            digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "91${digitsOnly.substring(1)}"
            digitsOnly.length == 12 && digitsOnly.startsWith("91") -> digitsOnly
            else -> digitsOnly
        }
    }

    private fun applyBlockedUI(ivProfile: ShapeableImageView) {
        val headerView: View = findViewById(R.id.detailHeader)
        headerView.setBackgroundColor(getColor(R.color.brand_red))
        ivProfile.setImageResource(R.drawable.ic_block)
        ivProfile.setBackgroundColor(getColor(R.color.brand_red))
        ivProfile.imageTintList = ColorStateList.valueOf(Color.WHITE)
        ivProfile.setPadding(30, 30, 30, 30)
    }

    private fun applyVerifiedUI(ivProfile: ShapeableImageView) {
        val headerView: View = findViewById(R.id.detailHeader)
        headerView.setBackgroundColor(getColor(R.color.verified_green))
        ivProfile.setImageResource(R.drawable.ic_person)
        ivProfile.setBackgroundColor(getColor(R.color.verified_green))
        ivProfile.imageTintList = ColorStateList.valueOf(Color.WHITE)
        ivProfile.setPadding(30, 30, 30, 30)
    }

    private fun updateBlockButtonUI() {
        val blockView: View = findViewById(R.id.actionBlock)
        val ivIcon = blockView.findViewById<ImageView>(R.id.ivActionIcon)
        val tvLabel = blockView.findViewById<TextView>(R.id.tvActionLabel)

        if (isBlockedLocally) {
            ivIcon.setImageResource(R.drawable.ic_block)
            ivIcon.setColorFilter(getColor(R.color.brand_red))
            tvLabel.text = "Unblock"
        } else {
            ivIcon.setImageResource(R.drawable.ic_block)
            ivIcon.setColorFilter(getColor(R.color.brand_red))
            tvLabel.text = "Block"
        }
    }

    private fun updateVerifiedButtonUI() {
        val verifiedView: View = findViewById(R.id.actionVerified)
        val ivIcon = verifiedView.findViewById<ImageView>(R.id.ivActionIcon)
        val tvLabel = verifiedView.findViewById<TextView>(R.id.tvActionLabel)

        if (isWhitelisted) {
            ivIcon.setColorFilter(getColor(R.color.verified_green))
            tvLabel.text = "Verified"
        } else {
            ivIcon.setColorFilter(Color.GRAY)
            tvLabel.text = "Verify"
        }
    }

    private fun checkContactStatus(phoneNumber: String, label: TextView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME
            )
            var exists = false
            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        exists = true
                    }
                }
            } catch (e: Exception) { }

            withContext(Dispatchers.Main) {
                label.visibility = if (exists) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupActionButtons(number: String, name: String) {
        // WhatsApp Action
        val whatsappView: View = findViewById(R.id.actionWhatsapp)
        whatsappView.findViewById<ImageView>(R.id.ivActionIcon).apply {
            setImageResource(R.drawable.ic_whatsapp)
            setColorFilter(getColor(R.color.verified_green))
        }
        whatsappView.findViewById<TextView>(R.id.tvActionLabel).text = "WhatsApp"
        whatsappView.setOnClickListener { handleWhatsappClick(number) }

        // Block Action
        val blockView: View = findViewById(R.id.actionBlock)
        blockView.setOnClickListener {
            if (isBlockedLocally) {
                handleUnblock(number)
            } else {
                showBlockReasonDialog(number, name)
            }
        }

        // Report Action
        val reportView: View = findViewById(R.id.actionReport)
        reportView.findViewById<ImageView>(R.id.ivActionIcon).apply {
            setImageResource(R.drawable.ic_shield)
            setColorFilter(getColor(R.color.brand_warning))
        }
        reportView.findViewById<TextView>(R.id.tvActionLabel).text = "Report"
        reportView.setOnClickListener { showReportConfirmDialog(number, name) }

        // Verified Action
        val verifiedView: View = findViewById(R.id.actionVerified)
        verifiedView.findViewById<ImageView>(R.id.ivActionIcon).setImageResource(R.drawable.ic_verified)
        verifiedView.setOnClickListener { 
            toggleWhitelist(number, name)
        }
    }

    private fun toggleWhitelist(number: String, name: String) {
        val currentUser = auth.currentUser ?: return
        val cleanNumber = normalizeNumber(number)
        val whitelistRef = db.collection("users").document(currentUser.uid).collection("white_list_numbers").document(cleanNumber)

        lifecycleScope.launch {
            try {
                val ivProfile: ShapeableImageView = findViewById(R.id.ivDetailProfile)
                if (isWhitelisted) {
                    whitelistRef.delete().await()
                    isWhitelisted = false
                    updateLocalNameCache(cleanNumber, null)
                    revertToDefaultUI(ivProfile)
                    Toast.makeText(this@ContactDetailsActivity, "Removed from White List", Toast.LENGTH_SHORT).show()
                } else {
                    val originalName = name.replace(" - online", "").trim()
                    
                    // Store WITHOUT suffix in DB
                    val data = mapOf(
                        "number" to cleanNumber,
                        "name" to originalName,
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                    whitelistRef.set(data).await()
                    isWhitelisted = true
                    
                    // Show WITH suffix in UI
                    val uiName = if (originalName == "Unknown" || originalName == number || originalName.isEmpty()) {
                        "Verified - online"
                    } else {
                        "$originalName - online"
                    }
                    
                    updateLocalNameCache(cleanNumber, uiName)
                    withContext(Dispatchers.Main) {
                        findViewById<TextView>(R.id.tvDetailName).text = uiName
                        applyVerifiedUI(ivProfile)
                    }

                    if (isBlockedLocally) {
                        handleUnblock(number)
                    }
                    
                    Toast.makeText(this@ContactDetailsActivity, "Added to White List", Toast.LENGTH_SHORT).show()
                }
                updateVerifiedButtonUI()
                updateLocalWhitelistCache(cleanNumber, isWhitelisted)
                
            } catch (e: Exception) {
                Toast.makeText(this@ContactDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLocalNameCache(number: String, finalName: String?) {
        val prefs = getSharedPreferences("DbNameCache", Context.MODE_PRIVATE)
        val json = prefs.getString("names", null)
        val cachedMap: MutableMap<String, String> = if (json != null) {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            Gson().fromJson(json, type)
        } else {
            mutableMapOf()
        }

        if (finalName != null) {
            cachedMap[number] = finalName
        } else {
            cachedMap.remove(number)
        }

        prefs.edit().putString("names", Gson().toJson(cachedMap)).apply()
    }

    private fun updateLocalWhitelistCache(number: String, isAdding: Boolean) {
        val prefs = getSharedPreferences("WhitelistCache", Context.MODE_PRIVATE)
        val cachedSet = prefs.getStringSet("white_set", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (isAdding) {
            cachedSet.add(number)
        } else {
            cachedSet.remove(number)
        }
        
        prefs.edit().putStringSet("white_set", cachedSet).apply()
    }

    private fun handleUnblock(number: String) {
        val currentUser = auth.currentUser ?: return
        val cleanNumber = normalizeNumber(number)

        lifecycleScope.launch {
            try {
                db.collection("users").document(currentUser.uid)
                    .collection("blocked_numbers").document(cleanNumber).delete().await()
                
                isBlockedLocally = false
                updateLocalBlockCache(cleanNumber, false)
                updateBlockButtonUI()
                
                val tvName: TextView = findViewById(R.id.tvDetailName)
                val ivProfile: ShapeableImageView = findViewById(R.id.ivDetailProfile)
                
                // If it's whitelisted, keep verified UI, otherwise revert
                if (isWhitelisted) {
                    applyVerifiedUI(ivProfile)
                } else {
                    revertToDefaultUI(ivProfile)
                    checkGlobalAndLocalStatus(number, tvName, ivProfile)
                }
                
                Toast.makeText(this@ContactDetailsActivity, "Unblocked", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ContactDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun revertToDefaultUI(ivProfile: ShapeableImageView) {
        val headerView: View = findViewById(R.id.detailHeader)
        headerView.setBackgroundColor(getColor(R.color.brand_black))
        ivProfile.setImageResource(R.drawable.ic_person)
        ivProfile.setBackgroundColor(getColor(R.color.brand_black))
        ivProfile.imageTintList = ColorStateList.valueOf(Color.WHITE)
        ivProfile.setPadding(30, 30, 30, 30)
    }

    private fun showBlockReasonDialog(number: String, name: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_block_reason, null)
        val etSuggestedName = dialogView.findViewById<EditText>(R.id.etSuggestedName)
        val cbForMeOnly = dialogView.findViewById<CheckBox>(R.id.cbForMeOnly)
        
        val checkBoxes = listOf(
            dialogView.findViewById<CheckBox>(R.id.cbSpam) to "Spam",
            dialogView.findViewById<CheckBox>(R.id.cbHarassment) to "Harassment",
            dialogView.findViewById<CheckBox>(R.id.cbFraud) to "Fraud",
            dialogView.findViewById<CheckBox>(R.id.cbOffensive) to "Offensive",
            dialogView.findViewById<CheckBox>(R.id.cbAbusing) to "Abusing",
            dialogView.findViewById<CheckBox>(R.id.cbTelemarketing) to "Telemarketing",
            dialogView.findViewById<CheckBox>(R.id.cbOther) to "Other"
        )

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Block") { _, _ ->
                val selectedReasons = checkBoxes.filter { it.first.isChecked }.map { it.second }
                val suggestedNameInput = etSuggestedName.text.toString().trim()
                val isForMeOnly = cbForMeOnly.isChecked
                
                val finalReasons = if (selectedReasons.isEmpty()) listOf("Spam") else selectedReasons
                val finalName = if (suggestedNameInput.isNotEmpty()) suggestedNameInput else "Spamer"

                handleGlobalReport(number, finalName, finalReasons, isBlock = true, isForMeOnly = isForMeOnly)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReportConfirmDialog(number: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Report Number")
            .setMessage("Do you want to report $number?")
            .setPositiveButton("Report") { _, _ ->
                handleGlobalReport(number, "Spamer", listOf("Spam"), isBlock = false, isForMeOnly = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleGlobalReport(number: String, finalName: String, reasons: List<String>, isBlock: Boolean, isForMeOnly: Boolean) {
        val currentUser = auth.currentUser ?: return
        val cleanNumber = normalizeNumber(number)
        if (cleanNumber.isEmpty()) return

        lifecycleScope.launch {
            try {
                val userProfile = db.collection("users").document(currentUser.uid).get().await()
                val isAdmin = userProfile.getString("role") == "Admin"

                val globalRef = db.collection("global_spam").document(cleanNumber)
                val reportDoc = globalRef.collection("reports").document(currentUser.uid).get().await()

                if (reportDoc.exists() && !isAdmin) {
                    Toast.makeText(this@ContactDetailsActivity, "You have already reported this number", Toast.LENGTH_SHORT).show()
                    if (isBlock) blockLocally(cleanNumber, finalName, reasons)
                    return@launch
                }

                blockLocally(cleanNumber, finalName, reasons)

                if (!isForMeOnly) {
                    val globalDoc = globalRef.get().await()
                    val exists = globalDoc.exists()
                    
                    val updates = mutableMapOf<String, Any>(
                        "isVerifiedSpam" to true,
                        "lastReported" to FieldValue.serverTimestamp(),
                        "reportCount" to FieldValue.increment(1)
                    )

                    if (!exists || isAdmin) {
                        updates["names"] = finalName
                        updates["primaryName"] = finalName
                    }

                    reasons.forEach { reason ->
                        val fieldName = reason.replace(" ", "")
                        updates["reasonCounts.$fieldName"] = FieldValue.increment(1)
                    }

                    db.runTransaction { transaction ->
                        transaction.set(globalRef, updates, SetOptions.merge())
                        transaction.set(globalRef.collection("reports").document(currentUser.uid), mapOf(
                            "uid" to currentUser.uid,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "reasons" to reasons,
                            "isBlock" to isBlock
                        ))
                    }.await()
                }

                Toast.makeText(this@ContactDetailsActivity, if (isForMeOnly) "Blocked locally" else "Reported successfully", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@ContactDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun blockLocally(cleanNumber: String, finalName: String, reasons: List<String>) {
        val currentUser = auth.currentUser ?: return
        val userBlockRef = db.collection("users").document(currentUser.uid).collection("blocked_numbers").document(cleanNumber)
        val personalUpdate = mapOf(
            "number" to cleanNumber,
            "name" to finalName,
            "reasons" to reasons,
            "timestamp" to FieldValue.serverTimestamp()
        )
        userBlockRef.set(personalUpdate).await()
        isBlockedLocally = true
        
        if (isWhitelisted) {
            db.collection("users").document(currentUser.uid).collection("white_list_numbers").document(cleanNumber).delete().await()
            isWhitelisted = false
            updateLocalWhitelistCache(cleanNumber, false)
            withContext(Dispatchers.Main) { updateVerifiedButtonUI() }
        }

        updateLocalBlockCache(cleanNumber, true)

        withContext(Dispatchers.Main) { 
            val ivProfile: ShapeableImageView = findViewById(R.id.ivDetailProfile)
            applyBlockedUI(ivProfile)
            updateBlockButtonUI() 
        }
    }

    private fun updateLocalBlockCache(number: String, isBlocking: Boolean) {
        val cleanNumber = normalizeNumber(number)
        val prefs = getSharedPreferences("BlockedNumbersCache", Context.MODE_PRIVATE)
        val cachedSet = prefs.getStringSet("blocked_set", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (isBlocking) {
            cachedSet.add(cleanNumber)
        } else {
            cachedSet.remove(cleanNumber)
        }
        
        prefs.edit().putStringSet("blocked_set", cachedSet).apply()
    }

    private fun handleWhatsappClick(number: String) {
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val savedPkg = prefs.getString("default_whatsapp_pkg", null)
        val installedApps = getInstalledWhatsappApps()
        
        if (installedApps.isEmpty()) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
            return
        }

        if (savedPkg != null && isAppInstalled(savedPkg)) {
            openWhatsapp(number, savedPkg)
        } else if (installedApps.size == 1) {
            openWhatsapp(number, installedApps[0].packageName)
        } else {
            showWhatsappSelectionDialog(number, installedApps)
        }
    }

    private fun getInstalledWhatsappApps(): List<WhatsappAppInfo> {
        val apps = mutableListOf<WhatsappAppInfo>()
        if (isAppInstalled(WHATSAPP_PKG)) apps.add(WhatsappAppInfo("WhatsApp", WHATSAPP_PKG))
        if (isAppInstalled(WHATSAPP_BUSINESS_PKG)) apps.add(WhatsappAppInfo("WhatsApp Business", WHATSAPP_BUSINESS_PKG))
        return apps
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun showWhatsappSelectionDialog(number: String, apps: List<WhatsappAppInfo>) {
        val names = apps.map { it.displayName }.toTypedArray()
        var selectedIdx = 0
        
        AlertDialog.Builder(this)
            .setTitle("Select WhatsApp Version")
            .setSingleChoiceItems(names, 0) { _, which ->
                selectedIdx = which
            }
            .setPositiveButton("Open") { _, _ ->
                openWhatsapp(number, apps[selectedIdx].packageName)
            }
            .setNeutralButton("Always Use This") { _, _ ->
                getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                    .edit()
                    .putString("default_whatsapp_pkg", apps[selectedIdx].packageName)
                    .apply()
                openWhatsapp(number, apps[selectedIdx].packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openWhatsapp(number: String, packageName: String) {
        val cleanNumber = number.replace("[^0-9]".toRegex(), "")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadActualCallHistory(contactNumber: String) {
        val container: LinearLayout = findViewById(R.id.llCallHistoryContainer)
        
        lifecycleScope.launch(Dispatchers.IO) {
            val historyList = mutableListOf<CallHistoryItem>()
            val normalizedSearch = contactNumber.replace("\\s".toRegex(), "").takeLast(10)

            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                "${CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%$normalizedSearch"),
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val accIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                while (it.moveToNext()) {
                    val number = it.getString(numIdx) ?: ""
                    val type = it.getInt(typeIdx)
                    val date = it.getLong(dateIdx)
                    val durationSeconds = it.getLong(durIdx)
                    val subscriptionId = try { it.getInt(accIdx) } catch (e: Exception) { -1 }

                    val callTypeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Incoming"
                    }
                    
                    val iconRes = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_incoming
                        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_call_missed
                        else -> R.drawable.ic_call_incoming
                    }

                    historyList.add(CallHistoryItem(
                        time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(date)),
                        type = callTypeStr,
                        number = number,
                        sim = if (subscriptionId <= 0) "1" else "2",
                        duration = formatDuration(durationSeconds),
                        iconRes = iconRes,
                        timestamp = date
                    ))
                }
            }

            val groupedHistory = historyList.groupBy { getGroupDate(it.timestamp) }
            withContext(Dispatchers.Main) {
                renderCallHistory(groupedHistory, container)
            }
        }
    }

    private fun renderCallHistory(history: Map<String, List<CallHistoryItem>>, container: LinearLayout) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        
        history.forEach { (date, items) ->
            val groupView = inflater.inflate(R.layout.item_call_history_group, container, false)
            groupView.findViewById<TextView>(R.id.tvDateGroup).text = date
            val itemsLayout = groupView.findViewById<LinearLayout>(R.id.llCallItems)

            items.forEachIndexed { index, item ->
                val itemView = inflater.inflate(R.layout.item_detail_call_log, itemsLayout, false)
                itemView.findViewById<TextView>(R.id.tvCallTime).text = item.time
                itemView.findViewById<TextView>(R.id.tvCallTypeLabel).text = item.type
                itemView.findViewById<TextView>(R.id.tvPhoneNumber).text = item.number
                itemView.findViewById<TextView>(R.id.tvSimLabel).text = item.sim
                itemView.findViewById<TextView>(R.id.tvDuration).text = item.duration
                
                val ivIcon = itemView.findViewById<ImageView>(R.id.ivCallType)
                ivIcon.setImageResource(item.iconRes)
                
                val iconColor = when(item.type) {
                    "Incoming" -> R.color.verified_green
                    "Missed" -> R.color.brand_red
                    "Outgoing" -> R.color.outgoing_blue
                    else -> R.color.text_primary
                }
                ivIcon.imageTintList = ColorStateList.valueOf(getColor(iconColor))

                itemsLayout.addView(itemView)
                
                if (index < items.size - 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            marginStart = 120 
                        }
                        setBackgroundColor(Color.parseColor("#1A000000"))
                    }
                    itemsLayout.addView(divider)
                }
            }
            container.addView(groupView)
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun getGroupDate(timestamp: Long): String {
        return when {
            DateUtils.isToday(timestamp) -> "Today"
            DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            else -> SimpleDateFormat("EEEE, dd MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }

    data class CallHistoryItem(
        val time: String,
        val type: String,
        val number: String,
        val sim: String,
        val duration: String,
        val iconRes: Int,
        val timestamp: Long
    )

    data class WhatsappAppInfo(val displayName: String, val packageName: String)
}
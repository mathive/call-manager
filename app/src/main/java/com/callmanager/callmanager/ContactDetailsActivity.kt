package com.callmanager.callmanager

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactDetailsActivity : AppCompatActivity() {

    private val whatsappPkg = "com.whatsapp"
    private val whatsappBusinessPkg = "com.whatsapp.w4b"
    private val whatsappPrefsName = "whatsapp_launch_prefs"
    private val whatsappChoiceKey = "preferred_package"
    private val observerHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var contactNumber: String = ""
    private var currentLookupSource: String? = null
    private var currentDisplayName: String = ""
    private var currentSpamReasonCounts: Map<String, Long> = emptyMap()
    private var currentIsBlockedByUser: Boolean = false
    private var currentIsWhitelistedByUser: Boolean = false

    private val callLogObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            observerHandler.removeCallbacks(callHistoryRefreshRunnable)
            observerHandler.removeCallbacks(contactRefreshRunnable)
            observerHandler.postDelayed(callHistoryRefreshRunnable, 400L)
        }
    }

    private val contactsObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            observerHandler.removeCallbacks(contactRefreshRunnable)
            observerHandler.postDelayed(contactRefreshRunnable, 400L)
        }
    }

    private val callHistoryRefreshRunnable = Runnable {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadActualCallHistory(contactNumber)
        }
    }

    private val contactRefreshRunnable = Runnable {
        refreshContactHeader()
    }

    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            refreshContactHeader()
            if (ContextCompat.checkSelfPermission(this@ContactDetailsActivity, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                loadActualCallHistory(contactNumber)
            }
            pollHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_details)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra("name") ?: getString(R.string.contact_name_placeholder)
        val number = intent.getStringExtra("number") ?: ""
        contactNumber = number
        currentLookupSource = intent.getStringExtra("lookupSource")
        currentDisplayName = name
        val photoUri = intent.getStringExtra("photoUri")

        findViewById<TextView>(R.id.tvDetailName).text = name
        bindProfile(photoUri, currentLookupSource, name)
        bindActionButtons(number)
        updateSpamInfoUi()

        refreshPersonalListState()
        refreshContactHeader()
        maybeResolveUnknownDetails()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadActualCallHistory(number)
        }
    }

    override fun onResume() {
        super.onResume()
        FirestoreUi.showPendingMessageIfAny(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
            loadActualCallHistory(contactNumber)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
            syncContactsToDb()
            refreshContactHeader()
        }
        refreshPersonalListState()
        pollHandler.removeCallbacksAndMessages(null)
        pollHandler.post(foregroundPollRunnable)
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(callLogObserver)
        runCatching { contentResolver.unregisterContentObserver(contactsObserver) }
        observerHandler.removeCallbacksAndMessages(null)
        pollHandler.removeCallbacksAndMessages(null)
    }

    private fun refreshContactHeader() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val label = findViewById<TextView>(R.id.tvInContactsLabel)
        val nameView = findViewById<TextView>(R.id.tvDetailName)

        lifecycleScope.launch(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(contactNumber))
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI
            )

            var displayName: String? = null
            var photoUri: String? = null
            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        displayName = cursor.getString(0)
                        photoUri = cursor.getString(1)
                    }
                }
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                label.visibility = if (displayName != null) View.VISIBLE else View.GONE
                currentDisplayName = displayName ?: currentDisplayName.ifBlank { contactNumber }
                nameView.text = currentDisplayName
                bindProfile(photoUri ?: intent.getStringExtra("photoUri"), currentLookupSource, currentDisplayName)
                bindActionButtons(contactNumber)
            }
        }
    }

    private fun bindProfile(photoUri: String?, lookupSource: String?, displayName: String) {
        val detailHeader: View = findViewById(R.id.detailHeader)
        val ivProfile: ShapeableImageView = findViewById(R.id.ivDetailProfile)
        val tvInitials: TextView = findViewById(R.id.tvDetailInitials)
        tvInitials.visibility = View.GONE
        ivProfile.setPadding(2, 2, 2, 2)
        ivProfile.strokeColor = ColorStateList.valueOf(getColor(R.color.white))
        ivProfile.strokeWidth = 3f
        detailHeader.setBackgroundColor(getColor(headerBackgroundColorRes(lookupSource)))

        if (!photoUri.isNullOrEmpty()) {
            Glide.with(this)
                .load(photoUri)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(ivProfile)
            ivProfile.imageTintList = null
        } else {
            when (lookupSource) {
                LookupSource.BLOCKED, LookupSource.SPAM -> {
                    ivProfile.setImageResource(R.drawable.ic_block)
                    ivProfile.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
                    ivProfile.setBackgroundColor(getColor(R.color.brand_red))
                    ivProfile.setPadding(26, 26, 26, 26)
                }
                LookupSource.WHITELIST -> {
                    ivProfile.setImageResource(R.drawable.ic_person)
                    ivProfile.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
                    ivProfile.setBackgroundColor(getColor(R.color.verified_green))
                    ivProfile.setPadding(30, 30, 30, 30)
                }
                LookupSource.VERIFIED -> {
                    ivProfile.setImageResource(R.drawable.ic_person)
                    ivProfile.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
                    ivProfile.setBackgroundColor(getColor(R.color.outgoing_blue))
                    ivProfile.setPadding(30, 30, 30, 30)
                }
                LookupSource.USER_CONTACT -> {
                    ivProfile.setImageResource(R.drawable.ic_person)
                    ivProfile.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
                    ivProfile.setBackgroundColor(getColor(R.color.brand_black))
                    ivProfile.setPadding(30, 30, 30, 30)
                }
                else -> {
                    ivProfile.setImageResource(R.drawable.ic_person)
                    ivProfile.imageTintList = ColorStateList.valueOf(Color.WHITE)
                    ivProfile.setBackgroundColor(getColor(R.color.brand_black))
                    ivProfile.setPadding(30, 30, 30, 30)
                }
            }
        }
    }

    private fun bindActionButtons(number: String) {
        val whatsappView: View = findViewById(R.id.actionWhatsapp)
        whatsappView.findViewById<ImageView>(R.id.ivActionIcon).apply {
            setImageResource(R.drawable.ic_whatsapp)
            setColorFilter(getColor(R.color.verified_green))
        }
        whatsappView.findViewById<TextView>(R.id.tvActionLabel).text = getString(R.string.whatsapp)
        whatsappView.setOnClickListener { handleWhatsappClick(number) }

        val isBlocked = currentIsBlockedByUser
        val isVerified = currentIsWhitelistedByUser ||
            currentLookupSource == LookupSource.VERIFIED ||
            currentLookupSource == LookupSource.USER_CONTACT

        bindAction(
            R.id.actionBlock,
            R.drawable.ic_block,
            if (isBlocked) R.string.unblock else R.string.block,
            if (isBlocked) R.color.brand_black else R.color.brand_red,
            if (isBlocked) R.color.blocked_card_bg else R.color.brand_white
        ) {
            toggleBlock()
        }
        bindDisabledAction(R.id.actionReport, R.drawable.ic_shield, R.string.report, R.color.brand_warning)
        val verifyView = findViewById<View>(R.id.actionVerified)
        if (currentLookupSource == LookupSource.USER_CONTACT) {
            verifyView.visibility = View.GONE
        } else {
            verifyView.visibility = View.VISIBLE
            bindAction(
                R.id.actionVerified,
                R.drawable.ic_verified,
                if (isVerified) R.string.unverify else R.string.verify,
                if (isVerified) R.color.outgoing_blue else R.color.text_secondary,
                R.color.brand_white
            ) {
                toggleWhiteList()
            }
        }
    }

    private fun refreshPersonalListState() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val normalizedNumber = normalizeLookupNumber(contactNumber) ?: contactNumber
        val documentIds = buildUserDocumentIds(normalizedNumber)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val firestore = FirebaseFirestore.getInstance()
                val userRef = firestore.collection("users").document(userId)
                val blockedExists = documentExistsInAnyVariant(userRef.collection("blocked_numbers"), documentIds)
                val whiteListedExists = documentExistsInAnyVariant(userRef.collection("white_list_numbers"), documentIds)
                withContext(Dispatchers.Main) {
                    currentIsBlockedByUser = blockedExists
                    currentIsWhitelistedByUser = whiteListedExists
                    bindActionButtons(contactNumber)
                }
            }
        }
    }

    private fun bindDisabledAction(viewId: Int, iconRes: Int, labelRes: Int, colorRes: Int, backgroundRes: Int = R.color.brand_white) {
        bindAction(viewId, iconRes, labelRes, colorRes, backgroundRes) {
            Toast.makeText(this, R.string.reset_flow_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindAction(
        viewId: Int,
        iconRes: Int,
        labelRes: Int,
        colorRes: Int,
        backgroundRes: Int = R.color.brand_white,
        onClick: () -> Unit
    ) {
        val actionView = findViewById<View>(viewId)
        actionView.findViewById<ImageView>(R.id.ivActionIcon).apply {
            setImageResource(iconRes)
            setColorFilter(getColor(colorRes))
            backgroundTintList = ColorStateList.valueOf(getColor(backgroundRes))
        }
        actionView.findViewById<TextView>(R.id.tvActionLabel).text = getString(labelRes)
        actionView.setOnClickListener { onClick() }
    }

    private fun handleWhatsappClick(number: String) {
        val cleanNumber = PhoneNumberVariants.toIndianMobileDigits(number)
            ?: PhoneNumberVariants.digitsOnly(number)
        if (cleanNumber.isBlank()) {
            Toast.makeText(this, R.string.could_not_open_whatsapp, Toast.LENGTH_SHORT).show()
            return
        }

        val installedPackages = buildList {
            if (isAppInstalled(whatsappPkg)) add(whatsappPkg)
            if (isAppInstalled(whatsappBusinessPkg)) add(whatsappBusinessPkg)
        }

        if (installedPackages.isEmpty()) {
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
            return
        }

        val rememberedPackage = getSharedPreferences(whatsappPrefsName, MODE_PRIVATE)
            .getString(whatsappChoiceKey, null)

        val packageToOpen = when {
            rememberedPackage != null && installedPackages.contains(rememberedPackage) -> rememberedPackage
            installedPackages.size == 1 -> installedPackages.first()
            else -> null
        }

        if (packageToOpen != null) {
            openWhatsappPackage(packageToOpen, cleanNumber)
            return
        }

        showWhatsappChooser(cleanNumber, installedPackages)
    }

    private fun showWhatsappChooser(number: String, installedPackages: List<String>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_whatsapp_picker, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.rgWhatsappApps)
        val rememberChoice = dialogView.findViewById<CheckBox>(R.id.cbRememberWhatsappChoice)

        installedPackages.forEachIndexed { index, packageName ->
            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (packageName == whatsappBusinessPkg) {
                    getString(R.string.whatsapp_business)
                } else {
                    getString(R.string.whatsapp)
                }
                textSize = 16f
                setTextColor(getColor(R.color.brand_black))
                buttonTintList = ColorStateList.valueOf(getColor(R.color.brand_red))
                minHeight = (52 * resources.displayMetrics.density).toInt()
                setPadding(0, 10, 0, 10)
                tag = packageName
            }
            radioGroup.addView(radioButton)
            if (index == 0) {
                radioButton.isChecked = true
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(getColor(R.color.brand_white)))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.brand_red))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.brand_red))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedRadioId = radioGroup.checkedRadioButtonId
                val selectedButton = dialogView.findViewById<RadioButton>(selectedRadioId)
                val selectedPackage = selectedButton?.tag as? String
                if (selectedPackage.isNullOrBlank()) {
                    Toast.makeText(this, R.string.select_whatsapp_version, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (rememberChoice.isChecked) {
                    getSharedPreferences(whatsappPrefsName, MODE_PRIVATE)
                        .edit()
                        .putString(whatsappChoiceKey, selectedPackage)
                        .apply()
                }
                dialog.dismiss()
                openWhatsappPackage(selectedPackage, number)
            }
        }
        dialog.show()
    }

    private fun openWhatsappPackage(packageName: String, cleanNumber: String) {
        val uri = "https://api.whatsapp.com/send?phone=$cleanNumber".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.could_not_open_whatsapp, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun checkContactStatus(phoneNumber: String, label: TextView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            var exists = false

            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) exists = true
                }
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                label.visibility = if (exists) View.VISIBLE else View.GONE
            }
        }
    }

    private fun maybeResolveUnknownDetails() {
        val initialName = intent.getStringExtra("name").orEmpty()
        if (!isUnknownName(currentDisplayName) || findViewById<TextView>(R.id.tvInContactsLabel).visibility == View.VISIBLE) {
            if (currentLookupSource == LookupSource.BLOCKED || currentLookupSource == LookupSource.SPAM) {
                loadSpamReasonCounts()
            }
            return
        }

        if (!isUnknownName(initialName) && !currentLookupSource.isNullOrBlank()) {
            if (currentLookupSource == LookupSource.BLOCKED || currentLookupSource == LookupSource.SPAM) {
                loadSpamReasonCounts()
            }
            return
        }

        getCachedLookup(contactNumber)?.let { cachedLookup ->
            applyResolvedLookup(cachedLookup)
            if (cachedLookup.source == LookupSource.BLOCKED || cachedLookup.source == LookupSource.SPAM) {
                loadSpamReasonCounts()
            }
            return
        }

        Toast.makeText(this, getString(R.string.searching_number, contactNumber), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val resolved = fetchRemoteNameForNumber(contactNumber)
            withContext(Dispatchers.Main) {
                if (resolved != null) {
                    persistResolvedLookup(contactNumber, resolved)
                    applyResolvedLookup(resolved)
                    Toast.makeText(
                        this@ContactDetailsActivity,
                        getString(R.string.found_number_as_name, contactNumber, resolved.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@ContactDetailsActivity,
                        getString(R.string.number_not_found, contactNumber),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun applyResolvedLookup(resolved: RemoteLookupResult) {
        currentLookupSource = resolved.source
        currentDisplayName = resolved.name
        currentSpamReasonCounts = resolved.spamReasonCounts
        findViewById<TextView>(R.id.tvDetailName).text = resolved.name
        bindProfile(intent.getStringExtra("photoUri"), currentLookupSource, currentDisplayName)
        bindActionButtons(contactNumber)
        updateSpamInfoUi()
    }

    private fun loadSpamReasonCounts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val spamInfo = lookupGlobalCollectionName(
                firestore = FirebaseFirestore.getInstance(),
                collectionName = "global_spam",
                documentIds = buildLookupDocumentIds(contactNumber),
                source = LookupSource.SPAM,
                requestedNumber = contactNumber
            )
            withContext(Dispatchers.Main) {
                currentSpamReasonCounts = spamInfo?.spamReasonCounts ?: emptyMap()
                updateSpamInfoUi()
            }
        }
    }

    private fun updateSpamInfoUi() {
        val container = findViewById<View>(R.id.spamInfoContainer)
        val chipGroup = findViewById<ChipGroup>(R.id.chipSpamReasons)
        if (currentSpamReasonCounts.isEmpty()) {
            container.visibility = View.GONE
            chipGroup.removeAllViews()
            return
        }

        chipGroup.removeAllViews()
        currentSpamReasonCounts
            .toList()
            .sortedByDescending { it.second }
            .forEach { (reason, count) ->
                val chip = Chip(this).apply {
                    text = "$reason $count"
                    isClickable = false
                    isCheckable = false
                    chipBackgroundColor = ColorStateList.valueOf(getColor(R.color.blocked_card_bg))
                    setTextColor(getColor(R.color.brand_red))
                    chipStrokeColor = ColorStateList.valueOf(getColor(R.color.brand_red))
                    chipStrokeWidth = 1f
                }
                chipGroup.addView(chip)
        }
        container.visibility = View.VISIBLE
    }

    private fun updateCachedLookupAfterWhiteListChange(number: String, remove: Boolean) {
        val snapshot = loadCacheSnapshot() ?: return
        val updatedLogs = snapshot.logs.map { item ->
            if (!sameNumber(item.number, number)) {
                item
            } else if (remove) {
                item.copy(
                    name = if (isUnknownName(item.name)) "" else item.name,
                    lookupSource = null,
                    isLookupInProgress = false
                )
            } else {
                item.copy(
                    name = currentDisplayName.ifBlank { number },
                    lookupSource = LookupSource.WHITELIST,
                    isLookupInProgress = false
                )
            }
        }
        saveCacheSnapshot(snapshot.copy(logs = updatedLogs))
        saveSearchedUnknownNumbers(number)
    }

    private fun updateCachedLookupAfterBlockChange(
        number: String,
        remove: Boolean,
        displayName: String,
        spamReasonCounts: Map<String, Long>
    ) {
        val snapshot = loadCacheSnapshot() ?: return
        val updatedLogs = snapshot.logs.map { item ->
            if (!sameNumber(item.number, number)) {
                item
            } else if (remove) {
                item.copy(
                    name = if (isUnknownName(item.name)) "" else item.name,
                    lookupSource = null,
                    isLookupInProgress = false
                )
            } else {
                item.copy(
                    name = displayName.ifBlank { number },
                    lookupSource = LookupSource.BLOCKED,
                    isLookupInProgress = false
                )
            }
        }
        saveCacheSnapshot(snapshot.copy(logs = updatedLogs))
        saveSearchedUnknownNumbers(number)
        currentSpamReasonCounts = if (remove) emptyMap() else spamReasonCounts
    }

    private fun toggleBlock() {
        if (currentIsBlockedByUser) {
            unblockNumber()
        } else {
            showBlockDialog()
        }
    }

    private fun showBlockDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_block_number, null, false)
        val nameInput = dialogView.findViewById<EditText>(R.id.etBlockName)
        val forMeOnly = dialogView.findViewById<CheckBox>(R.id.cbForMeOnly)
        val reasonChecks = linkedMapOf(
            "Spam" to dialogView.findViewById<CheckBox>(R.id.cbReasonSpam),
            "Harassment" to dialogView.findViewById<CheckBox>(R.id.cbReasonHarassment),
            "Fraud" to dialogView.findViewById<CheckBox>(R.id.cbReasonFraud),
            "Offensive" to dialogView.findViewById<CheckBox>(R.id.cbReasonOffensive),
            "Abusing" to dialogView.findViewById<CheckBox>(R.id.cbReasonAbusing),
            "Telemarketing" to dialogView.findViewById<CheckBox>(R.id.cbReasonTelemarketing),
            "Other" to dialogView.findViewById<CheckBox>(R.id.cbReasonOther)
        )

        fun updateReasonUiState(disabled: Boolean) {
            reasonChecks.values.forEach { checkbox ->
                checkbox.isEnabled = !disabled
                checkbox.alpha = if (disabled) 0.45f else 1f
            }
        }

        forMeOnly.setOnCheckedChangeListener { _, isChecked ->
            reasonChecks.values.forEach { checkbox ->
                if (isChecked) checkbox.isChecked = false
            }
            updateReasonUiState(isChecked)
        }
        updateReasonUiState(forMeOnly.isChecked)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.block_dialog_positive, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(getColor(R.color.brand_white)))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(getColor(R.color.brand_red))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.brand_red))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedReasons = reasonChecks.filterValues { it.isChecked }.keys.toList()
                if (forMeOnly.isChecked && selectedReasons.isNotEmpty()) {
                    Toast.makeText(this, R.string.for_me_only_conflict, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                blockNumber(
                    selectedReasons = selectedReasons,
                    forMeOnly = forMeOnly.isChecked,
                    customName = nameInput.text?.toString().orEmpty()
                )
            }
        }
        dialog.show()
    }

    private fun blockNumber(selectedReasons: List<String>, forMeOnly: Boolean, customName: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val normalizedNumber = normalizeLookupNumber(contactNumber) ?: contactNumber
        val documentId = normalizedNumber.removePrefix("+")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val userRef = firestore.collection("users").document(userId)
                val whiteListRef = userRef.collection("white_list_numbers").document(documentId)
                if (whiteListRef.get().await().exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ContactDetailsActivity, R.string.already_in_white_list_remove_first, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val userRole = userRef.get().await().getString("role").orEmpty()
                val isAdmin = userRole.equals("admin", ignoreCase = true)
                val resolvedName = resolveBlockedDisplayName(customName, selectedReasons, normalizedNumber)
                val reasonsToApply = if (forMeOnly) emptyList() else selectedReasons.ifEmpty { listOf("Spam") }
                val partialSpamDocRef = firestore.collection("partial_global_spam_list").document(normalizedNumber)
                val spamDocRef = firestore.collection("global_spam").document(normalizedNumber)
                val reporterMarkerRef = partialSpamDocRef.collection("reporters").document(userId)
                val spamResult = if (forMeOnly) {
                    SpamUpdateResult(false, 0L, emptyMap())
                } else {
                    firestore.runTransaction { transaction ->
                        val partialSnapshot = transaction.get(partialSpamDocRef)
                        val spamSnapshot = transaction.get(spamDocRef)
                        val reporterSnapshot = transaction.get(reporterMarkerRef)
                        val currentPartialCount = partialSnapshot.getLong("reportCount")
                            ?: spamSnapshot.getLong("reportCount")
                            ?: 0L
                        val currentReasonCounts = extractReasonCounts(
                            partialSnapshot.get("reasonCounts"),
                            partialSnapshot.data
                        ).ifEmpty {
                            extractReasonCounts(spamSnapshot.get("reasonCounts"), spamSnapshot.data)
                        }
                        val isAlreadyVerified = (spamSnapshot.getBoolean("isVerifiedSpam") == true) ||
                            (partialSnapshot.getBoolean("isVerifiedSpam") == true)
                        val alreadyReportedByUser = reporterSnapshot.exists()
                        val incrementedCount = if (alreadyReportedByUser) currentPartialCount else currentPartialCount + 1L
                        val promotedCount = when {
                            !alreadyReportedByUser && isAdmin && incrementedCount < 10L -> 10L
                            else -> incrementedCount
                        }
                        val shouldVerifySpam = isAlreadyVerified || isAdmin || promotedCount >= 10L

                        val mergedReasons = if (alreadyReportedByUser) {
                            currentReasonCounts
                        } else {
                            currentReasonCounts.toMutableMap().apply {
                                reasonsToApply.forEach { reason ->
                                    this[reason] = (this[reason] ?: 0L) + 1L
                                }
                            }.toMap()
                        }

                        if (!alreadyReportedByUser) {
                            transaction.set(
                                reporterMarkerRef,
                                hashMapOf(
                                    "uid" to userId,
                                    "reportedAt" to FieldValue.serverTimestamp(),
                                    "reportedByRole" to if (isAdmin) "Admin" else "Guest",
                                    "number" to normalizedNumber
                                ),
                                SetOptions.merge()
                            )
                        }

                        val partialUpdates = hashMapOf<String, Any>(
                            "phoneNumber" to normalizedNumber,
                            "number" to normalizedNumber,
                            "primaryName" to resolvedName,
                            "names" to resolvedName,
                            "isVerifiedSpam" to shouldVerifySpam,
                            "reportCount" to promotedCount,
                            "updated_at" to FieldValue.serverTimestamp()
                        )
                        if (!alreadyReportedByUser) {
                            partialUpdates["lastReported"] = FieldValue.serverTimestamp()
                            reasonsToApply.forEach { reason ->
                                partialUpdates["reasonCounts.$reason"] = mergedReasons[reason] ?: 1L
                            }
                        }
                        transaction.set(partialSpamDocRef, partialUpdates, SetOptions.merge())

                        if (shouldVerifySpam) {
                            val globalUpdates = hashMapOf<String, Any>(
                                "phoneNumber" to normalizedNumber,
                                "number" to normalizedNumber,
                                "primaryName" to resolvedName,
                                "names" to resolvedName,
                                "isVerifiedSpam" to true,
                                "reportCount" to promotedCount,
                                "updated_at" to FieldValue.serverTimestamp()
                            )
                            if (!alreadyReportedByUser) {
                                globalUpdates["lastReported"] = FieldValue.serverTimestamp()
                                reasonsToApply.forEach { reason ->
                                    globalUpdates["reasonCounts.$reason"] = mergedReasons[reason] ?: 1L
                                }
                            } else if (!spamSnapshot.exists()) {
                                mergedReasons.forEach { (reason, count) ->
                                    globalUpdates["reasonCounts.$reason"] = count
                                }
                            }
                            transaction.set(spamDocRef, globalUpdates, SetOptions.merge())
                        }

                        SpamUpdateResult(
                            isVerifiedSpam = shouldVerifySpam,
                            reportCount = promotedCount,
                            reasonCounts = mergedReasons
                        )
                    }.await()
                }

                userRef.collection("blocked_numbers")
                    .document(documentId)
                    .set(
                        hashMapOf(
                            "name" to resolvedName,
                            "primaryName" to resolvedName,
                            "number" to normalizedNumber,
                            "phoneNumber" to normalizedNumber,
                            "forMeOnly" to forMeOnly,
                            "reasons" to reasonsToApply,
                            "isVerifiedSpam" to spamResult.isVerifiedSpam,
                            "reportCount" to spamResult.reportCount,
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
                    .await()

                withContext(Dispatchers.Main) {
                    currentDisplayName = resolvedName
                    currentLookupSource = LookupSource.BLOCKED
                    currentIsBlockedByUser = true
                    currentIsWhitelistedByUser = false
                    updateCachedLookupAfterBlockChange(normalizedNumber, false, resolvedName, spamResult.reasonCounts)
                    bindProfile(intent.getStringExtra("photoUri"), currentLookupSource, currentDisplayName)
                    findViewById<TextView>(R.id.tvDetailName).text = currentDisplayName
                    bindActionButtons(contactNumber)
                    updateSpamInfoUi()
                    Toast.makeText(
                        this@ContactDetailsActivity,
                        if (forMeOnly) R.string.blocked_for_me_only else R.string.added_to_block_list,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    FirestoreUi.handleFailure(this@ContactDetailsActivity, e, "BlockToggle")
                }
            }
        }
    }

    private fun unblockNumber() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val normalizedNumber = normalizeLookupNumber(contactNumber) ?: contactNumber
        val documentIds = buildUserDocumentIds(normalizedNumber)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val blockedCollection = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("blocked_numbers")
                deleteAnyVariantDocuments(blockedCollection, documentIds)

                val resolved = fetchRemoteNameForNumber(contactNumber)
                withContext(Dispatchers.Main) {
                    currentIsBlockedByUser = false
                    updateCachedLookupAfterBlockChange(normalizedNumber, true, "", emptyMap())
                    if (resolved != null) {
                        persistResolvedLookup(contactNumber, resolved)
                        applyResolvedLookup(resolved)
                    } else {
                        currentLookupSource = null
                        currentSpamReasonCounts = emptyMap()
                        currentDisplayName = contactNumber
                        findViewById<TextView>(R.id.tvDetailName).text = currentDisplayName
                        bindProfile(intent.getStringExtra("photoUri"), currentLookupSource, currentDisplayName)
                        bindActionButtons(contactNumber)
                        updateSpamInfoUi()
                    }
                    Toast.makeText(this@ContactDetailsActivity, R.string.removed_from_block_list, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    FirestoreUi.handleFailure(this@ContactDetailsActivity, e, "UnblockToggle")
                }
            }
        }
    }

    private fun resolveBlockedDisplayName(customName: String, selectedReasons: List<String>, normalizedNumber: String): String {
        val trimmedName = customName.trim()
        return when {
            trimmedName.isNotBlank() -> trimmedName
            selectedReasons.isNotEmpty() -> selectedReasons.first()
            else -> getString(R.string.default_spam_reason).ifBlank { normalizedNumber }
        }
    }

    private fun toggleWhiteList() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val normalizedNumber = normalizeLookupNumber(contactNumber) ?: contactNumber
        val documentId = normalizedNumber.removePrefix("+")
        val documentIds = buildUserDocumentIds(normalizedNumber)
        val isCurrentlyVerified = currentLookupSource == LookupSource.WHITELIST ||
            currentLookupSource == LookupSource.VERIFIED

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val userRef = firestore
                    .collection("users")
                    .document(userId)
                val docRef = userRef
                    .collection("white_list_numbers")
                    .document(documentId)

                if (isCurrentlyVerified) {
                    docRef.delete().await()
                } else {
                    deleteAnyVariantDocuments(userRef.collection("blocked_numbers"), documentIds)
                    docRef.set(
                        hashMapOf(
                            "name" to currentDisplayName.ifBlank { normalizedNumber },
                            "number" to normalizedNumber,
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
                }

                withContext(Dispatchers.Main) {
                    if (isCurrentlyVerified) {
                        currentIsWhitelistedByUser = false
                        currentLookupSource = null
                        currentSpamReasonCounts = emptyMap()
                        Toast.makeText(this@ContactDetailsActivity, R.string.removed_from_white_list_short, Toast.LENGTH_SHORT).show()
                    } else {
                        currentIsBlockedByUser = false
                        currentIsWhitelistedByUser = true
                        currentLookupSource = LookupSource.WHITELIST
                        currentSpamReasonCounts = emptyMap()
                        Toast.makeText(this@ContactDetailsActivity, R.string.added_to_white_list_short, Toast.LENGTH_SHORT).show()
                    }
                    updateCachedLookupAfterWhiteListChange(normalizedNumber, isCurrentlyVerified)
                    bindProfile(intent.getStringExtra("photoUri"), currentLookupSource, currentDisplayName)
                    bindActionButtons(contactNumber)
                    updateSpamInfoUi()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    FirestoreUi.handleFailure(this@ContactDetailsActivity, e, "WhiteListToggle")
                }
            }
        }
    }

    private fun loadActualCallHistory(contactNumber: String) {
        val container: LinearLayout = findViewById(R.id.llCallHistoryContainer)

        lifecycleScope.launch(Dispatchers.IO) {
            val historyList = mutableListOf<CallHistoryItem>()
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
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
                    if (!PhoneNumberVariants.sameNumber(number, contactNumber)) {
                        continue
                    }
                    val type = it.getInt(typeIdx)
                    val date = it.getLong(dateIdx)
                    val durationSeconds = it.getLong(durIdx)
                    val subscriptionId = try {
                        if (accIdx >= 0) it.getInt(accIdx) else -1
                    } catch (_: Exception) {
                        -1
                    }

                    val presentation = CallLogTypeMapper.toPresentation(type)
                    historyList.add(
                        CallHistoryItem(
                            time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(date)),
                            type = presentation.label,
                            number = number,
                            sim = if (subscriptionId <= 0) "1" else "2",
                            duration = formatDuration(durationSeconds),
                            iconRes = presentation.iconRes,
                            timestamp = date
                        )
                    )
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
                val iconColor = when (item.type) {
                    "Incoming" -> R.color.verified_green
                    "Missed" -> R.color.brand_red
                    "Outgoing" -> R.color.outgoing_blue
                    else -> R.color.text_primary
                }
                ivIcon.imageTintList = ColorStateList.valueOf(getColor(iconColor))

                itemsLayout.addView(itemView)

                if (index < items.size - 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1
                        ).apply {
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
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
    }

    private fun getGroupDate(timestamp: Long): String {
        return when {
            DateUtils.isToday(timestamp) -> getString(R.string.detail_group_today)
            DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> getString(R.string.yesterday)
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

    private fun syncContactsToDb(force: Boolean = false) {
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        lifecycleScope.launch {
            try {
                ContactSyncManager.syncIfNeeded(this@ContactDetailsActivity, userId, force)
            } catch (e: Exception) {
                FirestoreUi.handleFailure(this@ContactDetailsActivity, e, "ContactSyncManager")
            }
        }
    }

    override fun onDestroy() {
        observerHandler.removeCallbacksAndMessages(null)
        pollHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private suspend fun fetchRemoteNameForNumber(number: String): RemoteLookupResult? {
        val firestore = FirebaseFirestore.getInstance()
        val documentIds = buildLookupDocumentIds(number)
        if (documentIds.isEmpty()) return null

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        var matchedSource: String? = null
        var matchedName: String? = null
        var spamReasonCounts: Map<String, Long> = emptyMap()

        if (!userId.isNullOrBlank()) {
            lookupUserSubcollectionName(firestore, userId, "blocked_numbers", documentIds, LookupSource.BLOCKED.toString(), number)?.let { probe ->
                matchedSource = matchedSource ?: probe.source
                if (matchedName.isNullOrBlank() && !probe.name.isNullOrBlank()) {
                    matchedName = probe.name
                }
            }
            lookupUserSubcollectionName(firestore, userId, "white_list_numbers", documentIds, LookupSource.WHITELIST.toString(), number)?.let { probe ->
                matchedSource = matchedSource ?: probe.source
                if (matchedName.isNullOrBlank() && !probe.name.isNullOrBlank()) {
                    matchedName = probe.name
                }
            }
        }

        lookupGlobalCollectionName(firestore, "global_verifed", documentIds, LookupSource.VERIFIED, number)?.let { probe ->
            matchedSource = matchedSource ?: probe.source
            if (matchedName.isNullOrBlank() && !probe.name.isNullOrBlank()) {
                matchedName = probe.name
            }
        }
        lookupGlobalCollectionName(firestore, "global_verified", documentIds, LookupSource.VERIFIED, number)?.let { probe ->
            matchedSource = matchedSource ?: probe.source
            if (matchedName.isNullOrBlank() && !probe.name.isNullOrBlank()) {
                matchedName = probe.name
            }
        }
        lookupGlobalCollectionName(firestore, "global_spam", documentIds, LookupSource.SPAM, number)?.let { probe ->
            if (spamReasonCounts.isEmpty() && probe.spamReasonCounts.isNotEmpty()) {
                spamReasonCounts = probe.spamReasonCounts
            }
            if (matchedSource.isNullOrBlank()) {
                matchedSource = probe.source
            }
            if (matchedName.isNullOrBlank() && !probe.name.isNullOrBlank()) {
                matchedName = probe.name
            }
        }

        lookupAllUsersContactsName(firestore, documentIds, LookupSource.USER_CONTACT.toString(), number)?.let { probe ->
            if (matchedSource.isNullOrBlank()) {
                matchedSource = probe.source
            }
            if (matchedName.isNullOrBlank() && !probe.name.isNullOrBlank()) {
                matchedName = probe.name
            }
        }

        return matchedSource?.let { RemoteLookupResult(matchedName ?: number, it, spamReasonCounts) }
    }

    private suspend fun lookupUserSubcollectionName(
        firestore: FirebaseFirestore,
        userId: String,
        subcollection: String,
        documentIds: List<String>,
        source: String,
        requestedNumber: String
    ): LookupProbeResult? {
        val userDocIds = documentIds.flatMap { listOf(it, it.removePrefix("+")) }.distinct()
        for (documentId in userDocIds) {
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection(subcollection)
                .document(documentId)
                .get()
                .await()
            if (snapshot.exists()) {
                return LookupProbeResult(
                    name = extractResolvedName(requestedNumber, snapshot.getString("primaryName"), snapshot.getString("name")),
                    source = source
                )
            }
        }
        return null
    }

    private suspend fun lookupGlobalCollectionName(
        firestore: FirebaseFirestore,
        collectionName: String,
        documentIds: List<String>,
        source: String,
        requestedNumber: String
    ): LookupProbeResult? {
        for (documentId in documentIds) {
            val snapshot = firestore.collection(collectionName).document(documentId).get().await()
            if (snapshot.exists()) {
                return LookupProbeResult(
                    name = extractResolvedName(requestedNumber, snapshot.getString("primaryName"), snapshot.getString("name")),
                    source = source,
                    spamReasonCounts = extractReasonCounts(snapshot.get("reasonCounts"), snapshot.data)
                )
            }
        }

        val queryCandidates = documentIds.distinct().take(10)
        if (queryCandidates.isEmpty()) return null

        firestore.collection(collectionName)
            .whereIn("phoneNumber", queryCandidates)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.let { document ->
                return LookupProbeResult(
                    name = extractResolvedName(requestedNumber, document.getString("primaryName"), document.getString("name")),
                    source = source,
                    spamReasonCounts = extractReasonCounts(document.get("reasonCounts"), document.data)
                )
            }

        firestore.collection(collectionName)
            .whereIn("number", queryCandidates)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.let { document ->
                return LookupProbeResult(
                    name = extractResolvedName(requestedNumber, document.getString("primaryName"), document.getString("name")),
                    source = source,
                    spamReasonCounts = extractReasonCounts(document.get("reasonCounts"), document.data)
                )
            }

        return null
    }

    private suspend fun lookupAllUsersContactsName(
        firestore: FirebaseFirestore,
        documentIds: List<String>,
        source: String,
        requestedNumber: String
    ): LookupProbeResult? {
        val queryCandidates = documentIds
            .flatMap { listOf(it, it.removePrefix("+")) }
            .distinct()
            .take(10)
        if (queryCandidates.isEmpty()) return null

        for (field in listOf("phoneNumber", "number")) {
            try {
                firestore.collectionGroup("contacts")
                    .whereIn(field, queryCandidates)
                    .get()
                    .await()
                    .documents
                    .firstOrNull()
                    ?.let { document ->
                        return LookupProbeResult(
                            name = extractResolvedName(requestedNumber, document.getString("primaryName"), document.getString("name")),
                            source = source
                        )
                    }
            } catch (e: Exception) {
                if (!isMissingContactsCollectionGroupIndex(e)) {
                    throw e
                }
            }
        }

        return fallbackLookupAllUsersContactsByDocId(firestore, queryCandidates, source, requestedNumber)
    }

    private suspend fun fallbackLookupAllUsersContactsByDocId(
        firestore: FirebaseFirestore,
        queryCandidates: List<String>,
        source: String,
        requestedNumber: String
    ): LookupProbeResult? {
        val userSnapshots = firestore.collection("users").limit(200).get().await()
        for (userDoc in userSnapshots.documents) {
            for (candidate in queryCandidates) {
                val snapshot = userDoc.reference
                    .collection("contacts")
                    .document(candidate)
                    .get()
                    .await()
                if (snapshot.exists()) {
                    return LookupProbeResult(
                        name = extractResolvedName(requestedNumber, snapshot.getString("primaryName"), snapshot.getString("name")),
                        source = source
                    )
                }
            }
        }

        return null
    }

    private fun isMissingContactsCollectionGroupIndex(error: Throwable): Boolean {
        val firestoreError = error as? FirebaseFirestoreException ?: return false
        if (firestoreError.code != FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
            return false
        }
        val message = firestoreError.message.orEmpty()
        return "collection contacts" in message && "index" in message.lowercase(Locale.getDefault())
    }

    private fun extractResolvedName(requestedNumber: String, vararg candidates: String?): String? {
        return candidates.firstNotNullOfOrNull { candidate ->
            val trimmed = candidate?.trim().orEmpty()
            when {
                trimmed.isBlank() -> null
                PhoneNumberVariants.sameNumber(trimmed, requestedNumber) -> null
                else -> trimmed
            }
        }
    }

    private fun extractReasonCounts(rawValue: Any?, documentData: Map<String, Any?>? = null): Map<String, Long> {
        val nestedMap = (rawValue as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
            val reason = key?.toString()?.trim().orEmpty()
            val count = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            if (reason.isBlank() || count == null || count <= 0L) null else reason to count
        }?.toMap().orEmpty()

        if (nestedMap.isNotEmpty()) return nestedMap

        val dottedMap = documentData.orEmpty().entries.mapNotNull { (key, value) ->
            if (!key.startsWith("reasonCounts.")) return@mapNotNull null
            val reason = key.removePrefix("reasonCounts.").trim()
            val count = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            if (reason.isBlank() || count == null || count <= 0L) null else reason to count
        }.toMap()

        return dottedMap
    }

    private fun normalizeLookupNumber(number: String): String? {
        return PhoneNumberVariants.toIndianMobilePlus(number)
            ?: PhoneNumberVariants.digitsOnly(number).takeIf { it.isNotBlank() }?.let { "+$it" }
    }

    private fun buildUserDocumentIds(number: String): List<String> {
        return PhoneNumberVariants.buildFirestoreDocumentIds(number)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun documentExistsInAnyVariant(
        collection: com.google.firebase.firestore.CollectionReference,
        documentIds: List<String>
    ): Boolean {
        for (documentId in documentIds) {
            if (collection.document(documentId).get().await().exists()) {
                return true
            }
        }
        return false
    }

    private suspend fun deleteAnyVariantDocuments(
        collection: com.google.firebase.firestore.CollectionReference,
        documentIds: List<String>
    ) {
        for (documentId in documentIds) {
            val docRef = collection.document(documentId)
            if (docRef.get().await().exists()) {
                docRef.delete().await()
            }
        }
    }

    private fun sameNumber(first: String, second: String): Boolean {
        return PhoneNumberVariants.sameNumber(first, second)
    }

    private fun buildLookupDocumentIds(number: String): List<String> {
        return PhoneNumberVariants.buildFirestoreDocumentIds(number)
    }

    private fun getCachedLookup(number: String): RemoteLookupResult? {
        val snapshot = loadCacheSnapshot() ?: return null
        return snapshot.logs.firstOrNull {
            sameNumber(it.number, number) && !it.lookupSource.isNullOrBlank() && !isUnknownName(it.name)
        }?.let { RemoteLookupResult(it.name, it.lookupSource!!, emptyMap()) }
    }

    private fun persistResolvedLookup(number: String, resolved: RemoteLookupResult) {
        val snapshot = loadCacheSnapshot() ?: return
        val updatedLogs = snapshot.logs.map { item ->
            if (sameNumber(item.number, number)) {
                item.copy(name = resolved.name, lookupSource = resolved.source, isLookupInProgress = false)
            } else {
                item
            }
        }

        saveCacheSnapshot(snapshot.copy(logs = updatedLogs))
        saveSearchedUnknownNumbers(number)
    }

    private fun loadCacheSnapshot(): DetailsCacheSnapshot? {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val json = prefs.getString(CACHE_KEY, null) ?: return null
        return runCatching {
            gson.fromJson<DetailsCacheSnapshot>(json, object : TypeToken<DetailsCacheSnapshot>() {}.type)
        }.getOrNull()
    }

    private fun saveCacheSnapshot(snapshot: DetailsCacheSnapshot) {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        prefs.edit().putString(CACHE_KEY, gson.toJson(snapshot)).apply()
    }

    private fun saveSearchedUnknownNumbers(number: String) {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val values = prefs.getStringSet(SEARCHED_UNKNOWN_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        buildLookupDocumentIds(number)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { values += it }
        prefs.edit().putStringSet(SEARCHED_UNKNOWN_KEY, values).apply()
    }

    private fun isUnknownName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        return trimmed.isBlank() || sameNumber(trimmed, contactNumber)
    }

    private fun getInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex())
        return if (parts.size >= 2) {
            (parts[0].firstOrNull()?.toString().orEmpty() + parts[1].firstOrNull()?.toString().orEmpty()).uppercase()
        } else {
            parts.firstOrNull()?.firstOrNull()?.toString()?.uppercase().orEmpty().ifBlank { "?" }
        }
    }

    private fun headerBackgroundColorRes(lookupSource: String?): Int {
        return when (lookupSource) {
            LookupSource.BLOCKED, LookupSource.SPAM -> R.color.brand_red
            LookupSource.WHITELIST -> R.color.verified_green
            LookupSource.VERIFIED -> R.color.outgoing_blue
            LookupSource.USER_CONTACT -> R.color.brand_black
            else -> R.color.brand_black
        }
    }

    private data class RemoteLookupResult(
        val name: String,
        val source: String,
        val spamReasonCounts: Map<String, Long> = emptyMap()
    )

    private data class LookupProbeResult(
        val name: String?,
        val source: String,
        val spamReasonCounts: Map<String, Long> = emptyMap()
    )

    private data class SpamUpdateResult(
        val isVerifiedSpam: Boolean,
        val reportCount: Long,
        val reasonCounts: Map<String, Long>
    )

    private data class DetailsCacheSnapshot(
        val latestId: String? = null,
        val latestDate: Long = 0L,
        val logs: List<CallLogItem> = emptyList()
    )

    private object LookupSource {
        const val BLOCKED = "blocked"
        const val SPAM = "spam"
        const val WHITELIST = "whitelist"
        const val VERIFIED = "verified"
        const val USER_CONTACT = "user_contact"
    }

    companion object {
        private const val CACHE_PREFS = "call_log_cache"
        private const val CACHE_KEY = "call_log_snapshot"
        private const val SEARCHED_UNKNOWN_KEY = "searched_unknown_numbers"
    }
}

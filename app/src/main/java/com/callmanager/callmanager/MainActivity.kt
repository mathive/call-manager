package com.callmanager.callmanager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var phoneNumberHintLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var rvCallLog: RecyclerView
    private lateinit var callLogAdapter: CallLogAdapter
    private lateinit var loadingOverlay: View
    private lateinit var loadingLogo: ImageView
    private val callLogList = mutableListOf<CallLogItem>()
    private var isDataReady = false
    private val gson = Gson()
    
    private var blockedNumbersListener: ListenerRegistration? = null
    private var blockedNumbersSet = mutableSetOf<String>()
    
    private var whiteListListener: ListenerRegistration? = null
    private var whiteListSet = mutableSetOf<String>()
    
    private val dbNameCache = mutableMapOf<String, String>()
    private val globalSpamSet = mutableSetOf<String>()
    private var activeTab = "Recents"

    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            loadCallLogInBackground()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasCallLogPermission = permissions[Manifest.permission.READ_CALL_LOG] == true
        val hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] == true

        if (hasCallLogPermission) {
            checkPermissionAndLoadLogs()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }

        if (hasContactsPermission) {
            scheduleContactSync()
        }
    }

    private val roleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Call Screening Role Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Role Denied - Blocking will not work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isDataReady }

        super.onCreate(savedInstanceState)
        
        phoneNumberHintLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                try {
                    var phoneNumber = Identity.getSignInClient(this).getPhoneNumberFromIntent(result.data)
                    if (phoneNumber.startsWith("+1") && phoneNumber.length == 12) {
                        phoneNumber = "+91" + phoneNumber.substring(2)
                    }
                    updatePhoneNumber(phoneNumber)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to get phone number: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            handleDeepLink()
            return
        }
        checkUserProfile(currentUser.uid, currentUser.email ?: "", currentUser.displayName)
        requestCallScreeningRole()
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                roleRequestLauncher.launch(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        if (isDataReady) {
            // Reload caches from SharedPreferences to stay in sync with other activities
            loadBlockedNumbersCache()
            loadWhiteListCache()
            loadDbNameCache()
            loadCallLogInBackground()
        }
        if (activeTab == "Profile") {
            switchTab("Recents")
        }
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(callLogObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedNumbersListener?.remove()
        whiteListListener?.remove()
    }

    private fun checkUserProfile(uid: String, email: String, googleName: String?) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val mobileNumber = document.getString("mobileNumber")
                    val fullName = document.getString("fullName") ?: "User"
                    if (mobileNumber.isNullOrEmpty()) {
                        promptForPhoneNumber()
                    } else {
                        setupMainUI(fullName)
                    }
                } else {
                    createNewUserProfile(uid, email, googleName)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                auth.signOut()
                navigateToLogin()
            }
    }

    private fun createNewUserProfile(uid: String, email: String, googleName: String?) {
        db.collection("pending_users").document(email).get()
            .addOnSuccessListener { pendingDoc ->
                val fullName = if (pendingDoc.exists()) {
                    pendingDoc.getString("fullName") ?: "User"
                } else {
                    googleName ?: "User"
                }
                val userData = hashMapOf(
                    "fullName" to fullName,
                    "email" to email,
                    "isVerified" to true,
                    "role" to "Guest"
                )
                db.collection("users").document(uid).set(userData)
                    .addOnSuccessListener {
                        db.collection("pending_users").document(email).delete()
                        promptForPhoneNumber()
                    }
            }
    }

    private fun promptForPhoneNumber() {
        val request = GetPhoneNumberHintIntentRequest.builder().build()
        Identity.getSignInClient(this)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener { result ->
                try {
                    phoneNumberHintLauncher.launch(IntentSenderRequest.Builder(result).build())
                } catch (e: Exception) {
                    setupMainUI(auth.currentUser?.displayName ?: "User")
                }
            }
            .addOnFailureListener {
                setupMainUI(auth.currentUser?.displayName ?: "User")
            }
    }

    private fun updatePhoneNumber(phoneNumber: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).update("mobileNumber", phoneNumber)
            .addOnSuccessListener {
                db.collection("users").document(uid).get().addOnSuccessListener { doc ->
                    setupMainUI(doc.getString("fullName") ?: "User")
                }
            }
    }

    private fun handleDeepLink() {
        val data = intent.data
        if (data == null) {
            navigateToLogin()
            return
        }
        val emailLink = data.toString()
        if (auth.isSignInWithEmailLink(emailLink)) {
            val sharedPref = getSharedPreferences("Auth", Context.MODE_PRIVATE)
            val email = sharedPref.getString("email", "") ?: ""
            auth.signInWithEmailLink(email, emailLink)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        recreate()
                    } else {
                        navigateToLogin()
                    }
                }
        } else {
            navigateToLogin()
        }
    }

    private fun setupMainUI(fullName: String) {
        setContentView(R.layout.activity_main)
        rvCallLog = findViewById(R.id.rvCallLog)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingLogo = findViewById(R.id.loadingLogo)
        rvCallLog.layoutManager = LinearLayoutManager(this)
        
        loadFromCache()
        loadBlockedNumbersCache()
        loadWhiteListCache()
        loadDbNameCache() // Load cached online names
        
        callLogAdapter = CallLogAdapter(callLogList)
        rvCallLog.adapter = callLogAdapter
        
        findViewById<EditText>(R.id.etSearchMain).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.tabRecents).setOnClickListener { switchTab("Recents") }
        findViewById<LinearLayout>(R.id.tabBlockList).setOnClickListener { switchTab("BlockList") }
        findViewById<LinearLayout>(R.id.tabWhiteList).setOnClickListener { switchTab("WhiteList") }
        findViewById<LinearLayout>(R.id.tabProfile).setOnClickListener { switchTab("Profile") }

        findViewById<FloatingActionButton>(R.id.fabRefresh).setOnClickListener {
            refreshCache()
        }
        
        setupBlockedNumbersListener()
        setupWhiteListListener()
        checkPermissionAndLoadLogs()
        scheduleContactSync()
    }

    private fun startLoadingAnimation() {
        loadingOverlay.visibility = View.VISIBLE
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1500
            repeatCount = Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        loadingLogo.startAnimation(rotate)
    }

    private fun stopLoadingAnimation() {
        loadingLogo.clearAnimation()
        loadingOverlay.visibility = View.GONE
    }

    private fun refreshCache() {
        Toast.makeText(this, "Refreshing cache and refetching data...", Toast.LENGTH_SHORT).show()
        
        // 1. Clear all memory caches
        dbNameCache.clear()
        globalSpamSet.clear()
        blockedNumbersSet.clear()
        whiteListSet.clear()
        
        // 2. Clear all persistent caches
        getSharedPreferences("CallLogCache", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("DbNameCache", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("BlockedNumbersCache", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("WhitelistCache", Context.MODE_PRIVATE).edit().clear().apply()
        
        // 3. Reset UI and trigger full reload
        startLoadingAnimation()
        callLogList.clear()
        updateDisplayList()
        
        // 4. Reload device logs and online names
        loadCallLogInBackground()
        
        // 5. Re-trigger listeners
        blockedNumbersListener?.remove()
        setupBlockedNumbersListener()
        whiteListListener?.remove()
        setupWhiteListListener()
    }

    private fun switchTab(tab: String) {
        if (tab == "Profile") {
            startActivity(Intent(this, ProfileActivity::class.java))
            return
        }

        activeTab = tab
        
        val tabRecents = findViewById<LinearLayout>(R.id.tabRecents)
        val tabBlockList = findViewById<LinearLayout>(R.id.tabBlockList)
        val tabWhiteList = findViewById<LinearLayout>(R.id.tabWhiteList)
        val tabProfile = findViewById<LinearLayout>(R.id.tabProfile)

        // Reset all tabs
        val tabs = listOf(tabRecents, tabBlockList, tabWhiteList, tabProfile)
        tabs.forEach { t ->
            t.setBackgroundColor(Color.TRANSPARENT)
            val icon = t.getChildAt(0) as ImageView
            val label = t.getChildAt(1) as TextView
            icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            label.setTextColor(Color.WHITE)
        }

        // Highlight active tab
        val activeView = when(tab) {
            "Recents" -> tabRecents
            "BlockList" -> tabBlockList
            "WhiteList" -> tabWhiteList
            else -> tabProfile
        }
        activeView.setBackgroundColor(getColor(R.color.brand_red))

        updateDisplayList()
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

    private fun updateDisplayList() {
        if (!::callLogAdapter.isInitialized) return

        val displayList = when (activeTab) {
            "BlockList" -> {
                callLogList
                    .filter { blockedNumbersSet.contains(normalizeNumber(it.number)) }
                    .distinctBy { normalizeNumber(it.number) }
            }
            "WhiteList" -> {
                callLogList
                    .filter { whiteListSet.contains(normalizeNumber(it.number)) }
                    .distinctBy { normalizeNumber(it.number) }
            }
            else -> callLogList
        }

        callLogAdapter = CallLogAdapter(displayList)
        rvCallLog.adapter = callLogAdapter
    }

    private fun loadBlockedNumbersCache() {
        val prefs = getSharedPreferences("BlockedNumbersCache", Context.MODE_PRIVATE)
        val cachedSet = prefs.getStringSet("blocked_set", emptySet())
        blockedNumbersSet.clear()
        blockedNumbersSet.addAll(cachedSet ?: emptySet())
    }

    private fun saveBlockedNumbersCache() {
        val prefs = getSharedPreferences("BlockedNumbersCache", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("blocked_set", blockedNumbersSet).apply()
    }

    private fun setupBlockedNumbersListener() {
        val uid = auth.currentUser?.uid ?: return
        blockedNumbersListener = db.collection("users").document(uid).collection("blocked_numbers")
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                
                val newBlockedSet = snapshots?.documents?.mapNotNull { it.id }?.toMutableSet() ?: mutableSetOf()
                if (newBlockedSet != blockedNumbersSet) {
                    blockedNumbersSet.clear()
                    blockedNumbersSet.addAll(newBlockedSet)
                    saveBlockedNumbersCache()
                    updateLogsStatus()
                }
            }
    }

    private fun loadWhiteListCache() {
        val prefs = getSharedPreferences("WhitelistCache", Context.MODE_PRIVATE)
        val cachedSet = prefs.getStringSet("white_set", emptySet())
        whiteListSet.clear()
        whiteListSet.addAll(cachedSet ?: emptySet())
    }

    private fun saveWhiteListCache() {
        val prefs = getSharedPreferences("WhitelistCache", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("white_set", whiteListSet).apply()
    }

    private fun setupWhiteListListener() {
        val uid = auth.currentUser?.uid ?: return
        whiteListListener = db.collection("users").document(uid).collection("white_list_numbers")
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                
                val newWhiteSet = snapshots?.documents?.mapNotNull { it.id }?.toMutableSet() ?: mutableSetOf()
                if (newWhiteSet != whiteListSet) {
                    whiteListSet.clear()
                    whiteListSet.addAll(newWhiteSet)
                    saveWhiteListCache()
                    updateLogsStatus()
                }
            }
    }

    private fun updateLogsStatus() {
        var changed = false
        callLogList.forEach { item ->
            val cleanNum = normalizeNumber(item.number)
            val isBlocked = blockedNumbersSet.contains(cleanNum)
            val isVerified = whiteListSet.contains(cleanNum)
            val isSpam = globalSpamSet.contains(cleanNum)
            
            // Check if name has changed in cache
            val dbName = dbNameCache[cleanNum]
            if (item.dbName != dbName) {
                item.dbName = dbName
                changed = true
            }
            
            if (item.isBlockedLocally != isBlocked || item.isGlobalSpam != isSpam || item.isWhitelisted != isVerified) {
                item.isBlockedLocally = isBlocked
                item.isWhitelisted = isVerified
                item.isGlobalSpam = isSpam
                changed = true
            }
        }
        if (changed || activeTab != "Recents") {
            updateDisplayList()
        }
    }

    private fun scheduleContactSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<ContactSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ContactSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun loadFromCache() {
        val prefs = getSharedPreferences("CallLogCache", Context.MODE_PRIVATE)
        val json = prefs.getString("logs", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<CallLogItem>>() {}.type
                val cachedList: List<CallLogItem> = gson.fromJson(json, type)
                callLogList.clear()
                callLogList.addAll(cachedList)
                isDataReady = true
            } catch (e: Exception) { }
        }
    }

    private fun saveToCache(list: List<CallLogItem>) {
        val prefs = getSharedPreferences("CallLogCache", Context.MODE_PRIVATE)
        val json = gson.toJson(list)
        prefs.edit().putString("logs", json).apply()
    }

    private fun loadDbNameCache() {
        val prefs = getSharedPreferences("DbNameCache", Context.MODE_PRIVATE)
        val json = prefs.getString("names", null)
        if (json != null) {
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val cachedMap: Map<String, String> = gson.fromJson(json, type)
                dbNameCache.clear()
                dbNameCache.putAll(cachedMap)
            } catch (e: Exception) { }
        }
    }

    private fun saveDbNameCache() {
        val prefs = getSharedPreferences("DbNameCache", Context.MODE_PRIVATE)
        val json = gson.toJson(dbNameCache)
        prefs.edit().putString("names", json).apply()
    }

    private fun checkPermissionAndLoadLogs() {
        val callLogPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
        val contactsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)

        if (callLogPermission == PackageManager.PERMISSION_GRANTED && contactsPermission == PackageManager.PERMISSION_GRANTED) {
            loadCallLogInBackground()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            )
        }
    }

    private fun loadCallLogInBackground() {
        if (callLogList.isEmpty()) {
            startLoadingAnimation()
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            val tempList = mutableListOf<CallLogItem>()
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null, CallLog.Calls.DATE + " DESC"
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val accIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                while (it.moveToNext()) {
                    val number = it.getString(numIdx) ?: ""
                    val type = it.getInt(typeIdx)
                    val date = it.getLong(dateIdx)
                    val subscriptionId = try { it.getInt(accIdx) } catch (e: Exception) { -1 }

                    val (liveName, livePhoto) = getContactDetails(number)

                    val callTypeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Unknown"
                    }
                    val simId = if (subscriptionId <= 0) 1 else 2
                    
                    val cleanNum = normalizeNumber(number)
                    val isBlocked = blockedNumbersSet.contains(cleanNum)
                    val isVerified = whiteListSet.contains(cleanNum)
                    val isSpam = globalSpamSet.contains(cleanNum)
                    val dbName = dbNameCache[cleanNum]
                    
                    tempList.add(CallLogItem(liveName ?: "", number, callTypeStr, date, simId, livePhoto, isBlocked, dbName, isSpam, isVerified))
                }
            }

            if (tempList.isNotEmpty()) {
                saveToCache(tempList)
            }

            withContext(Dispatchers.Main) {
                stopLoadingAnimation()
                callLogList.clear()
                callLogList.addAll(tempList)
                updateDisplayList()
                isDataReady = true
                
                fetchGlobalData()
            }
        }
    }

    private fun fetchGlobalData() {
        val unknownNumbers = callLogList
            .filter { it.name.isNullOrEmpty() || it.name == it.number }
            .map { normalizeNumber(it.number) }
            .filter { it.isNotEmpty() && !dbNameCache.containsKey(it) }
            .distinct()

        if (unknownNumbers.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            unknownNumbers.chunked(30).forEach { chunk ->
                // 1. global_spam
                try {
                    val spamSnap = db.collection("global_spam")
                        .whereIn(FieldPath.documentId(), chunk)
                        .get().await()
                    
                    spamSnap.documents.forEach { doc ->
                        val cleanNum = doc.id
                        val name = doc.getString("primaryName") ?: doc.getString("name")
                        if (!name.isNullOrEmpty()) {
                            val isBlocked = blockedNumbersSet.contains(cleanNum)
                            val finalName = if (isBlocked) name else "$name - online"
                            val isVerified = doc.getBoolean("isVerifiedSpam") ?: false
                            dbNameCache[cleanNum] = finalName
                            saveDbNameCache()
                            withContext(Dispatchers.Main) {
                                updateItemStatus(cleanNum, finalName, isVerified)
                            }
                        }
                    }
                } catch (e: Exception) { }

                // 2. users
                try {
                    val userSnap = db.collection("users")
                        .whereIn("mobileNumber", chunk)
                        .get().await()
                    
                    userSnap.documents.forEach { doc ->
                        val cleanNum = doc.getString("mobileNumber") ?: ""
                        if (cleanNum.isNotEmpty() && !dbNameCache.containsKey(cleanNum)) {
                            val name = doc.getString("fullName") ?: doc.getString("name")
                            if (!name.isNullOrEmpty()) {
                                val isBlocked = blockedNumbersSet.contains(cleanNum)
                                val finalName = if (isBlocked) name else "$name - online"
                                dbNameCache[cleanNum] = finalName
                                saveDbNameCache()
                                withContext(Dispatchers.Main) {
                                    updateItemStatus(cleanNum, finalName, false)
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }

                // 3. Community Contacts
                try {
                    val contactSnap = db.collectionGroup("contacts")
                        .whereIn("number", chunk)
                        .get().await()
                    
                    contactSnap.documents.forEach { doc ->
                        val cleanNum = normalizeNumber(doc.getString("number") ?: "")
                        if (cleanNum.isNotEmpty() && !dbNameCache.containsKey(cleanNum)) {
                            val name = doc.getString("name")
                            if (!name.isNullOrEmpty()) {
                                val isBlocked = blockedNumbersSet.contains(cleanNum)
                                val finalName = if (isBlocked) name else "$name - online"
                                dbNameCache[cleanNum] = finalName
                                saveDbNameCache()
                                withContext(Dispatchers.Main) {
                                    updateItemStatus(cleanNum, finalName, false)
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun updateItemStatus(number: String, name: String?, isSpam: Boolean) {
        var changed = false
        callLogList.forEach { item ->
            if (normalizeNumber(item.number) == number) {
                if (name != null) item.dbName = name
                if (isSpam) item.isGlobalSpam = true
                changed = true
            }
        }
        if (changed) {
            updateDisplayList()
        }
    }

    private fun getContactDetails(phoneNumber: String): Pair<String?, String?> {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val photo = cursor.getString(1)
                    Pair(name, photo)
                } else Pair(null, null)
            } ?: Pair(null, null)
        } catch (e: Exception) { Pair(null, null) }
    }

    private fun navigateToLogin() {
        isDataReady = true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}

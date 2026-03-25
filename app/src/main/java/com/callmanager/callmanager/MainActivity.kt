package com.callmanager.callmanager

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
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
import android.provider.Settings
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class CallLogItem(
    var name: String,
    val number: String,
    val type: String,
    val time: Long,
    val simId: Int = 1,
    val photoUri: String? = null,
    val callCount: Int = 1,
    val isLookupInProgress: Boolean = false,
    val lookupSource: String? = null
)

private data class CallLogCacheSnapshot(
    val latestId: String? = null,
    val latestDate: Long = 0L,
    val logs: List<CallLogItem> = emptyList()
)

class MainActivity : AppCompatActivity() {
    private val auth = FirebaseAuth.getInstance()
    private val gson = Gson()
    private lateinit var rvCallLog: RecyclerView
    private lateinit var callLogAdapter: CallLogAdapter
    private lateinit var loadingOverlay: View
    private lateinit var loadingLogo: ImageView
    private lateinit var loadingAnimator: ObjectAnimator
    private val callLogList = mutableListOf<CallLogItem>()
    private val blockListItems = mutableListOf<CallLogItem>()
    private val whiteListItems = mutableListOf<CallLogItem>()
    private var activeTab = "Recents"
    private var cachedLatestId: String? = null
    private var cachedLatestDate: Long = 0L
    private val observerHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private var isSyncInProgress = false
    private var pendingSyncRequested = false
    private var hasCheckedProfileBootstrap = false
    private var isProfileBootstrapInProgress = false
    private var pendingProfileBootstrap: PendingProfileBootstrap? = null
    private var isUnknownLookupRunning = false
    private var pendingUnknownLookupRequested = false
    private val unknownLookupCache = mutableMapOf<String, RemoteLookupResult>()
    private val searchedUnknownNumbers = mutableSetOf<String>()
    private var visibleLookupNumbers = emptySet<String>()
    private val viewportLookupRunnable = Runnable { scheduleUnknownNumberLookup() }
    private var isListTabSyncInProgress = false
    private var pendingRefreshCompletionLabel: String? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val CACHE_PREFS = "call_log_cache"
        private const val CACHE_KEY = "call_log_snapshot"
        private const val SEARCHED_UNKNOWN_KEY = "searched_unknown_numbers"
        private const val BATCH_SIZE = 100
        private const val RECENT_SYNC_SIZE = 50
        private const val UNKNOWN_LOOKUP_BATCH_SIZE = 10
        private const val CHANGE_DEBOUNCE_MS = 400L
        private const val POLL_INTERVAL_MS = 2000L
        private const val CALL_END_REFRESH_DELAY_MS = 900L
        private const val LIST_TAB_PLACEHOLDER_TIME = 0L
    }

    private val callLogObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            observerHandler.removeCallbacks(syncRunnable)
            observerHandler.removeCallbacks(contactSyncRunnable)
            observerHandler.postDelayed(syncRunnable, CHANGE_DEBOUNCE_MS)
        }
    }

    private val contactsObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            observerHandler.removeCallbacks(contactSyncRunnable)
            observerHandler.postDelayed(contactSyncRunnable, CHANGE_DEBOUNCE_MS)
        }
    }

    private val syncRunnable = Runnable {
        if (auth.currentUser != null && getMissingRuntimePermissions().isEmpty()) {
            syncRecentLogs(showOverlay = false)
        }
    }

    private val contactSyncRunnable = Runnable {
        if (auth.currentUser != null && getMissingRuntimePermissions().isEmpty()) {
            syncContactsToDb()
            refreshCachedContactDetails()
            syncRecentLogs(showOverlay = false)
        }
    }

    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            if (auth.currentUser != null && getMissingRuntimePermissions().isEmpty()) {
                syncRecentLogs(showOverlay = false)
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE
    private val telephonyManager by lazy {
        getSystemService(TelephonyManager::class.java)
    }
    private val callStateCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChanged(state)
            }
        }
    } else {
        null
    }
    private val callEndedRefreshRunnable = Runnable {
        if (auth.currentUser != null && getMissingRuntimePermissions().isEmpty()) {
            syncRecentLogs(showOverlay = false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (Settings.canDrawOverlays(this)) {
                syncAllLogsInBatches(showOverlay = callLogList.isEmpty())
            } else {
                showOverlayDialog()
            }
        } else {
            val deniedPermanently = getMissingRuntimePermissions().any {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            }
            if (deniedPermanently) {
                showAppSettingsDialog()
            } else {
                Toast.makeText(this, R.string.permissions_required_all, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (auth.currentUser == null) {
            navigateToLogin()
            return
        }

        rvCallLog = findViewById(R.id.rvCallLog)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingLogo = findViewById(R.id.loadingLogo)
        rvCallLog.layoutManager = LinearLayoutManager(this)
        callLogAdapter = CallLogAdapter(callLogList)
        rvCallLog.adapter = callLogAdapter
        rvCallLog.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateVisibleUnknownLookupTargets()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                updateVisibleUnknownLookupTargets()
            }
        })
        loadingAnimator = ObjectAnimator.ofFloat(loadingLogo, View.ROTATION_Y, 0f, 360f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }

        findViewById<EditText>(R.id.etSearchMain).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.tabRecents).setOnClickListener { switchTab("Recents") }
        findViewById<LinearLayout>(R.id.tabBlockList).setOnClickListener { switchTab("BlockList") }
        findViewById<LinearLayout>(R.id.tabWhiteList).setOnClickListener { switchTab("WhiteList") }
        findViewById<LinearLayout>(R.id.tabProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<FloatingActionButton>(R.id.fabRefresh).setOnClickListener {
            handleRefreshAction()
        }

        restoreCache()
    }

    override fun onResume() {
        super.onResume()
        FirestoreUi.showPendingMessageIfAny(this)
        if (auth.currentUser == null) {
            navigateToLogin()
            return
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
            contentResolver.registerContentObserver(ContactsContract.Data.CONTENT_URI, true, contactsObserver)
            contentResolver.registerContentObserver(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, true, contactsObserver)
            refreshCachedContactDetails()
            syncContactsToDb()
        }
        registerCallStateListenerIfPossible()
        pollHandler.removeCallbacksAndMessages(null)
        pollHandler.post(foregroundPollRunnable)
        ensurePermissionsOrLoad()
        rvCallLog.post { updateVisibleUnknownLookupTargets() }
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(callLogObserver)
        runCatching { contentResolver.unregisterContentObserver(contactsObserver) }
        observerHandler.removeCallbacksAndMessages(null)
        unregisterCallStateListener()
        pollHandler.removeCallbacksAndMessages(null)
    }

    private fun ensurePermissionsOrLoad(forceReload: Boolean = false) {
        val missingRuntimePermissions = getMissingRuntimePermissions()
        when {
            missingRuntimePermissions.isNotEmpty() -> {
                val shouldShowRationale = missingRuntimePermissions.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }
                if (shouldShowRationale) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.permission_required_title)
                        .setMessage(R.string.permission_required_message)
                        .setPositiveButton(R.string.enable) { _, _ ->
                            requestPermissionLauncher.launch(missingRuntimePermissions.toTypedArray())
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    requestPermissionLauncher.launch(missingRuntimePermissions.toTypedArray())
                }
            }
            !Settings.canDrawOverlays(this) -> {
                showOverlayDialog()
            }
            forceReload -> {
                syncAllLogsInBatches(showOverlay = callLogList.isEmpty())
            }
            else -> {
                syncRecentLogs(showOverlay = callLogList.isEmpty())
            }
        }
    }

    private fun restoreCache() {
        val snapshot = loadCacheSnapshot() ?: return
        loadSearchedUnknownNumbers()
        cachedLatestId = snapshot.latestId
        cachedLatestDate = snapshot.latestDate
        callLogList.clear()
        callLogList.addAll(snapshot.logs)
        callLogList
            .filter { !isUnknownEntry(it) && !it.lookupSource.isNullOrBlank() }
            .forEach { item ->
                unknownLookupCache[item.number] = RemoteLookupResult(item.name, item.lookupSource!!)
            }
        updateDisplayList()
        maybeBootstrapCurrentUserProfile()
        updateVisibleUnknownLookupTargets()
    }

    private fun syncRecentLogs(showOverlay: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentLogs = queryRecentLogs(RECENT_SYNC_SIZE)
            val latestRecord = recentLogs.firstOrNull()?.let {
                buildGroupedLogKey(it) to it.time
            }

            if (recentLogs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (showOverlay) showLoadingOverlay()
                    applyRecentLogs(recentLogs)
                    if (showOverlay) hideLoadingOverlay()
                }
            }

            val shouldSyncFull = recentLogs.isEmpty() ||
                latestRecord == null ||
                latestRecord.first != cachedLatestId ||
                latestRecord.second != cachedLatestDate ||
                callLogList.size < recentLogs.size

            if (shouldSyncFull) {
                withContext(Dispatchers.Main) {
                    syncAllLogsInBatches(showOverlay = false)
                }
            }
        }
    }

    private fun queryRecentLogs(limit: Int): List<CallLogItem> {
        val recentLogs = mutableListOf<CallLogItem>()
        val contactCache = mutableMapOf<String, Pair<String?, String?>>()
        val cursor: Cursor? = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.PHONE_ACCOUNT_ID
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use {
            val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val accIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (it.moveToNext() && recentLogs.size < limit) {
                val number = it.getString(numIdx) ?: ""
                val type = it.getInt(typeIdx)
                val date = it.getLong(dateIdx)
                val subscriptionId = try {
                    if (accIdx >= 0) it.getInt(accIdx) else -1
                } catch (_: Exception) {
                    -1
                }
                val (name, photoUri) = contactCache.getOrPut(number) {
                    getContactDetails(number)
                }
                recentLogs += applyCachedLookupResolution(
                    CallLogItem(
                    name = name ?: "",
                    number = number,
                    type = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Unknown"
                    },
                    time = date,
                    simId = if (subscriptionId <= 0) 1 else 2,
                    photoUri = photoUri
                    )
                )
            }
        }

        return groupConsecutiveCalls(recentLogs).take(limit)
    }

    private fun applyRecentLogs(recentLogs: List<CallLogItem>) {
        val merged = LinkedHashMap<String, CallLogItem>()
        recentLogs.forEach { merged[buildGroupedLogKey(it)] = it }
        callLogList.forEach { item ->
            merged.putIfAbsent(buildGroupedLogKey(item), item)
        }

        val updatedList = merged.values.toList()
        callLogList.clear()
        callLogList.addAll(updatedList)
        updateDisplayList()
        maybeBootstrapCurrentUserProfile()
        updateVisibleUnknownLookupTargets()

        val latestItem = recentLogs.firstOrNull()
        cachedLatestId = latestItem?.let { buildGroupedLogKey(it) }
        cachedLatestDate = latestItem?.time ?: 0L
        saveCacheSnapshot(
            CallLogCacheSnapshot(
                latestId = cachedLatestId,
                latestDate = cachedLatestDate,
                logs = updatedList
            )
        )
    }

    private fun syncAllLogsInBatches(showOverlay: Boolean) {
        if (isSyncInProgress) {
            pendingSyncRequested = true
            return
        }
        isSyncInProgress = true
        if (showOverlay) {
            showLoadingOverlay()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val allLogs = mutableListOf<CallLogItem>()
            val batch = mutableListOf<CallLogItem>()
            val contactCache = mutableMapOf<String, Pair<String?, String?>>()
            var latestId: String? = null
            var latestDate = 0L
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.PHONE_ACCOUNT_ID
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val accIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                while (it.moveToNext()) {
                    val rowId = it.getString(idIdx)
                    val number = it.getString(numIdx) ?: ""
                    val type = it.getInt(typeIdx)
                    val date = it.getLong(dateIdx)
                    val subscriptionId = try {
                        if (accIdx >= 0) it.getInt(accIdx) else -1
                    } catch (_: Exception) {
                        -1
                    }

                    if (latestId == null) {
                        latestId = rowId
                        latestDate = date
                    }

                    val (name, photoUri) = contactCache.getOrPut(number) {
                        getContactDetails(number)
                    }
                    val callTypeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Unknown"
                    }

                    batch += applyCachedLookupResolution(
                        CallLogItem(
                        name = name ?: "",
                        number = number,
                        type = callTypeStr,
                        time = date,
                        simId = if (subscriptionId <= 0) 1 else 2,
                        photoUri = photoUri
                        )
                    )

                    if (batch.size == BATCH_SIZE) {
                        allLogs.addAll(batch)
                        publishBatch(
                            accumulatedLogs = groupConsecutiveCalls(allLogs),
                            latestId = latestId,
                            latestDate = latestDate,
                            hideOverlay = false
                        )
                        batch.clear()
                    }
                }
            }

            if (batch.isNotEmpty()) {
                allLogs.addAll(batch)
                publishBatch(
                    accumulatedLogs = groupConsecutiveCalls(allLogs),
                    latestId = latestId,
                    latestDate = latestDate,
                    hideOverlay = false
                )
            }

            withContext(Dispatchers.Main) {
                isSyncInProgress = false
                if (showOverlay) {
                    hideLoadingOverlay()
                }
                if (allLogs.isEmpty()) {
                    callLogList.clear()
                    updateDisplayList()
                    Toast.makeText(this@MainActivity, R.string.no_call_logs_found, Toast.LENGTH_SHORT).show()
                }
                pendingRefreshCompletionLabel?.let { label ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.refreshed_tab, label),
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingRefreshCompletionLabel = null
                }
                if (pendingSyncRequested) {
                    pendingSyncRequested = false
                    syncAllLogsInBatches(showOverlay = false)
                }
            }
        }
    }

    private suspend fun publishBatch(
        accumulatedLogs: List<CallLogItem>,
        latestId: String?,
        latestDate: Long,
        hideOverlay: Boolean
    ) {
        saveCacheSnapshot(
            CallLogCacheSnapshot(
                latestId = latestId ?: accumulatedLogs.firstOrNull()?.let { buildGroupedLogKey(it) },
                latestDate = latestDate,
                logs = accumulatedLogs
            )
        )
        withContext(Dispatchers.Main) {
            cachedLatestId = latestId ?: accumulatedLogs.firstOrNull()?.let { buildGroupedLogKey(it) }
            cachedLatestDate = if (latestDate != 0L) latestDate else accumulatedLogs.firstOrNull()?.time ?: 0L
            callLogList.clear()
            callLogList.addAll(accumulatedLogs)
            updateDisplayList()
            maybeBootstrapCurrentUserProfile()
            updateVisibleUnknownLookupTargets()
            if (hideOverlay) {
                hideLoadingOverlay()
            }
        }
    }

    private fun buildLogKey(item: CallLogItem): String {
        return "${item.number}|${item.time}|${item.type}|${item.simId}"
    }

    private fun buildGroupedLogKey(item: CallLogItem): String {
        return "${item.number}|${item.time}|${item.type}|${item.simId}|${item.callCount}"
    }

    private fun groupConsecutiveCalls(logs: List<CallLogItem>): List<CallLogItem> {
        if (logs.isEmpty()) return emptyList()

        val grouped = mutableListOf<CallLogItem>()
        var current = logs.first()
        var count = current.callCount

        for (index in 1 until logs.size) {
            val next = logs[index]
            if (next.number == current.number) {
                count += next.callCount
            } else {
                grouped += current.copy(callCount = count)
                current = next
                count = current.callCount
            }
        }

        grouped += current.copy(callCount = count)
        return grouped
    }

    private fun showLoadingOverlay() {
        loadingOverlay.visibility = View.VISIBLE
        loadingAnimator.cancel()
        loadingLogo.rotationY = 0f
        loadingAnimator.start()
    }

    private fun hideLoadingOverlay() {
        loadingAnimator.cancel()
        loadingLogo.rotationY = 0f
        loadingOverlay.visibility = View.GONE
    }

    private fun getContactDetails(phoneNumber: String): Pair<String?, String?> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Pair(null, null)
        }

        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Pair(cursor.getString(0), cursor.getString(1))
                } else {
                    Pair(null, null)
                }
            } ?: Pair(null, null)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun rebuildCachesAndRefresh() {
        resetLookupCaches()
        ensurePermissionsOrLoad(forceReload = true)
    }

    private fun handleRefreshAction() {
        when (activeTab) {
            "BlockList" -> {
                Toast.makeText(this, getString(R.string.refreshing_tab, getTabDisplayName(activeTab)), Toast.LENGTH_SHORT).show()
                syncLookupCollectionIntoUi("blocked_numbers", LookupSource.BLOCKED, true)
            }
            "WhiteList" -> {
                Toast.makeText(this, getString(R.string.refreshing_tab, getTabDisplayName(activeTab)), Toast.LENGTH_SHORT).show()
                syncLookupCollectionIntoUi("white_list_numbers", LookupSource.WHITELIST, true)
            }
            else -> {
                pendingRefreshCompletionLabel = getTabDisplayName(activeTab)
                Toast.makeText(this, getString(R.string.refreshing_tab, pendingRefreshCompletionLabel), Toast.LENGTH_SHORT).show()
                rebuildCachesAndRefresh()
            }
        }
    }

    private fun refreshCachedContactDetails() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val currentItems = callLogList.toList()
            if (currentItems.isEmpty()) return@launch

            val contactCache = mutableMapOf<String, Pair<String?, String?>>()
            val refreshedItems = currentItems.map { item ->
                val (name, photoUri) = contactCache.getOrPut(item.number) {
                    getContactDetails(item.number)
                }
                val updatedItem = if (!name.isNullOrBlank()) {
                    item.copy(
                        name = name,
                        photoUri = photoUri,
                        lookupSource = null
                    )
                } else {
                    applyCachedLookupResolution(
                        item.copy(
                            name = "",
                            photoUri = photoUri
                        )
                    )
                }
                updatedItem
            }

            saveCacheSnapshot(
                CallLogCacheSnapshot(
                    latestId = cachedLatestId,
                    latestDate = cachedLatestDate,
                    logs = refreshedItems
                )
            )

            withContext(Dispatchers.Main) {
                callLogList.clear()
                callLogList.addAll(refreshedItems)
                updateDisplayList()
                updateVisibleUnknownLookupTargets()
            }
        }
    }

    private fun scheduleUnknownNumberLookup() {
        if (auth.currentUser == null) return
        if (isUnknownLookupRunning) {
            pendingUnknownLookupRequested = true
            return
        }

        val pendingNumbers = callLogList
            .filter { it.number in visibleLookupNumbers && isUnknownEntry(it) && !it.isLookupInProgress }
            .map { it.number }
            .distinct()
            .filter { shouldLookupUnknownNumber(it) }

        if (pendingNumbers.isEmpty()) return

        isUnknownLookupRunning = true
        lifecycleScope.launch {
            try {
                processUnknownNumberLookupQueue()
            } finally {
                isUnknownLookupRunning = false
                if (pendingUnknownLookupRequested) {
                    pendingUnknownLookupRequested = false
                    scheduleUnknownNumberLookup()
                }
            }
        }
    }

    private suspend fun processUnknownNumberLookupQueue() {
        while (true) {
            val batch = callLogList
                .filter { it.number in visibleLookupNumbers && isUnknownEntry(it) && !it.isLookupInProgress }
                .map { it.number }
                .distinct()
                .filter { shouldLookupUnknownNumber(it) }
                .take(UNKNOWN_LOOKUP_BATCH_SIZE)

            if (batch.isEmpty()) return

            markLookupState(batch, true)

            val results = withContext(Dispatchers.IO) {
                batch.associateWith { number -> fetchRemoteNameForNumber(number) }
            }

            results.forEach { (number, resolvedLookup) ->
                searchedUnknownNumbers += number
                if (resolvedLookup != null) {
                    unknownLookupCache[number] = resolvedLookup
                }
            }
            saveSearchedUnknownNumbers()

            applyLookupResults(results)
        }
    }

    private suspend fun fetchRemoteNameForNumber(number: String): RemoteLookupResult? {
        val firestore = FirebaseFirestore.getInstance()
        val documentIds = buildLookupDocumentIds(number)
        if (documentIds.isEmpty()) return null

        val userId = auth.currentUser?.uid
        var matchedSource: String? = null

        if (!userId.isNullOrBlank()) {
            lookupUserSubcollectionName(
                firestore,
                userId,
                "blocked_numbers",
                documentIds,
                LookupSource.BLOCKED,
                number
            )?.let { probe ->
                matchedSource = matchedSource ?: probe.source
                if (!probe.name.isNullOrBlank()) {
                    return RemoteLookupResult(probe.name, matchedSource!!)
                }
            }
            lookupUserSubcollectionName(
                firestore,
                userId,
                "white_list_numbers",
                documentIds,
                LookupSource.WHITELIST,
                number
            )?.let { probe ->
                matchedSource = matchedSource ?: probe.source
                if (!probe.name.isNullOrBlank()) {
                    return RemoteLookupResult(probe.name, matchedSource!!)
                }
            }
        }

        lookupGlobalCollectionName(firestore, "global_verifed", documentIds, LookupSource.VERIFIED, number)?.let { probe ->
            matchedSource = matchedSource ?: probe.source
            if (!probe.name.isNullOrBlank()) {
                return RemoteLookupResult(probe.name, matchedSource!!)
            }
        }
        lookupGlobalCollectionName(firestore, "global_verified", documentIds, LookupSource.VERIFIED, number)?.let { probe ->
            matchedSource = matchedSource ?: probe.source
            if (!probe.name.isNullOrBlank()) {
                return RemoteLookupResult(probe.name, matchedSource!!)
            }
        }
        lookupGlobalCollectionName(firestore, "global_spam", documentIds, LookupSource.SPAM, number)?.let { probe ->
            matchedSource = matchedSource ?: probe.source
            if (!probe.name.isNullOrBlank()) {
                return RemoteLookupResult(probe.name, matchedSource!!)
            }
        }

        lookupAllUsersContactsName(
            firestore,
            documentIds,
            LookupSource.USER_CONTACT,
            number
        )?.let { probe ->
            matchedSource = matchedSource ?: probe.source
            if (!probe.name.isNullOrBlank()) {
                return RemoteLookupResult(probe.name, matchedSource!!)
            }
        }
        return matchedSource?.let { RemoteLookupResult(number, it) }
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
            val snapshot = firestore
                .collection("users")
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
            val snapshot = firestore
                .collection(collectionName)
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

        val queryCandidates = documentIds.distinct().take(10)
        if (queryCandidates.isEmpty()) return null

        val phoneFieldMatches = firestore
            .collection(collectionName)
            .whereIn("phoneNumber", queryCandidates)
            .get()
            .await()
        phoneFieldMatches.documents.firstOrNull()?.let { document ->
            return LookupProbeResult(
                name = extractResolvedName(requestedNumber, document.getString("primaryName"), document.getString("name")),
                source = source
            )
        }

        val legacyNumberMatches = firestore
            .collection(collectionName)
            .whereIn("number", queryCandidates)
            .get()
            .await()
        legacyNumberMatches.documents.firstOrNull()?.let { document ->
            return LookupProbeResult(
                name = extractResolvedName(requestedNumber, document.getString("primaryName"), document.getString("name")),
                source = source
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

        firestore.collectionGroup("contacts")
            .whereIn("phoneNumber", queryCandidates)
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

        firestore.collectionGroup("contacts")
            .whereIn("number", queryCandidates)
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

        return null
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

    private fun buildLookupDocumentIds(number: String): List<String> {
        return PhoneNumberVariants.buildFirestoreDocumentIds(number)
    }

    private fun markLookupState(numbers: List<String>, inProgress: Boolean) {
        val updatedItems = callLogList.map { item ->
            if (item.number in numbers && isUnknownEntry(item)) {
                item.copy(isLookupInProgress = inProgress)
            } else {
                item
            }
        }

        callLogList.clear()
        callLogList.addAll(updatedItems)
        updateDisplayList()
        saveCurrentCacheSnapshot()
    }

    private fun applyLookupResults(results: Map<String, RemoteLookupResult?>) {
        val updatedItems = callLogList.map { item ->
            val resolvedLookup = results[item.number]
            when {
                item.number !in results -> item
                resolvedLookup == null -> item.copy(isLookupInProgress = false)
                else -> item.copy(
                    name = resolvedLookup.name,
                    isLookupInProgress = false,
                    lookupSource = resolvedLookup.source
                )
            }
        }

        callLogList.clear()
        callLogList.addAll(updatedItems)
        updateDisplayList()
        saveCurrentCacheSnapshot()
    }

    private fun saveCurrentCacheSnapshot() {
        saveCacheSnapshot(
            CallLogCacheSnapshot(
                latestId = cachedLatestId,
                latestDate = cachedLatestDate,
                logs = callLogList.toList()
            )
        )
    }

    private fun applyCachedLookupResolution(item: CallLogItem): CallLogItem {
        if (!item.name.isBlank()) return item
        val cachedLookup = unknownLookupCache[item.number] ?: return item
        return item.copy(
            name = cachedLookup.name,
            lookupSource = cachedLookup.source
        )
    }

    private fun loadSearchedUnknownNumbers() {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        searchedUnknownNumbers.clear()
        searchedUnknownNumbers.addAll(prefs.getStringSet(SEARCHED_UNKNOWN_KEY, emptySet()) ?: emptySet())
    }

    private fun saveSearchedUnknownNumbers() {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        prefs.edit().putStringSet(SEARCHED_UNKNOWN_KEY, searchedUnknownNumbers.toSet()).apply()
    }

    private fun resetLookupCaches() {
        unknownLookupCache.clear()
        searchedUnknownNumbers.clear()
        saveSearchedUnknownNumbers()
        val resetItems = callLogList.map { item ->
            if (item.lookupSource.isNullOrBlank()) {
                item.copy(isLookupInProgress = false)
            } else {
                item.copy(
                    name = "",
                    isLookupInProgress = false,
                    lookupSource = null
                )
            }
        }
        callLogList.clear()
        callLogList.addAll(resetItems)
        saveCurrentCacheSnapshot()
        updateDisplayList()
        rvCallLog.post { updateVisibleUnknownLookupTargets() }
    }

    private fun updateVisibleUnknownLookupTargets() {
        val layoutManager = rvCallLog.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION || callLogList.isEmpty()) {
            visibleLookupNumbers = emptySet()
            return
        }

        val safeFirst = first.coerceAtLeast(0)
        val safeLast = last.coerceAtMost(callLogList.lastIndex)
        visibleLookupNumbers = callLogList
            .subList(safeFirst, safeLast + 1)
            .filter { isUnknownEntry(it) }
            .map { it.number }
            .toSet()

        rvCallLog.removeCallbacks(viewportLookupRunnable)
        rvCallLog.post(viewportLookupRunnable)
    }

    private fun shouldLookupUnknownNumber(number: String): Boolean {
        if (number.isBlank()) return false
        if (searchedUnknownNumbers.contains(number)) return false
        if (unknownLookupCache.containsKey(number)) return false
        return true
    }

    private fun isUnknownEntry(item: CallLogItem): Boolean {
        return item.name.isBlank() || item.name == item.number
    }

    private data class RemoteLookupResult(
        val name: String,
        val source: String
    )

    private data class LookupProbeResult(
        val name: String?,
        val source: String
    )

    private object LookupSource {
        const val BLOCKED = "blocked"
        const val SPAM = "spam"
        const val WHITELIST = "whitelist"
        const val VERIFIED = "verified"
        const val USER_CONTACT = "user_contact"
    }

    private fun maybeBootstrapCurrentUserProfile() {
        if (hasCheckedProfileBootstrap || pendingProfileBootstrap != null || isProfileBootstrapInProgress) return
        val currentUser = auth.currentUser ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        lifecycleScope.launch {
            isProfileBootstrapInProgress = true
            try {
                val bootstrap = withContext(Dispatchers.IO) {
                    UserProfileBootstrapper.getPendingBootstrap(this@MainActivity, currentUser)
                }
                if (bootstrap == null) {
                    hasCheckedProfileBootstrap = true
                    isProfileBootstrapInProgress = false
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    UserProfileBootstrapper.applyBootstrap(bootstrap, null)
                }

                pendingProfileBootstrap = bootstrap
                launchPhoneNumberHint(bootstrap)
            } catch (_: Exception) {
                isProfileBootstrapInProgress = false
                pendingProfileBootstrap = null
                hasCheckedProfileBootstrap = true
                Toast.makeText(this@MainActivity, R.string.profile_bootstrap_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val phoneNumberHintLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val bootstrap = pendingProfileBootstrap ?: run {
            hasCheckedProfileBootstrap = true
            return@registerForActivityResult
        }

        val selectedNumber = try {
            val dataIntent = result.data ?: return@registerForActivityResult handlePhoneNumberHintDismissed(bootstrap)
            Identity.getSignInClient(this).getPhoneNumberFromIntent(dataIntent)
        } catch (_: ApiException) {
            null
        } catch (_: Exception) {
            null
        }
        Log.d(TAG, "Phone hint resultCode=${result.resultCode} selectedNumber=$selectedNumber")

        if (selectedNumber.isNullOrBlank()) {
            Toast.makeText(this, "No number selected", Toast.LENGTH_SHORT).show()
            handlePhoneNumberHintDismissed(bootstrap)
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UserProfileBootstrapper.applyBootstrap(bootstrap, selectedNumber)
                }
                Toast.makeText(this@MainActivity, "Number updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply bootstrap for selectedNumber=$selectedNumber", e)
                Toast.makeText(this@MainActivity, R.string.profile_bootstrap_failed, Toast.LENGTH_SHORT).show()
            } finally {
                isProfileBootstrapInProgress = false
                pendingProfileBootstrap = null
                hasCheckedProfileBootstrap = true
            }
        }
    }

    private fun launchPhoneNumberHint(bootstrap: PendingProfileBootstrap) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Launching Google phone hint for userId=${bootstrap.userId}")
                val request = GetPhoneNumberHintIntentRequest.builder().build()
                val pendingIntent = Identity.getSignInClient(this@MainActivity)
                    .getPhoneNumberHintIntent(request)
                    .await()
                val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
                phoneNumberHintLauncher.launch(intentSenderRequest)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Google phone hint", e)
                handlePhoneNumberHintDismissed(bootstrap)
            }
        }
    }

    private fun handlePhoneNumberHintDismissed(bootstrap: PendingProfileBootstrap) {
        lifecycleScope.launch {
            try {
                // Keep profile basics already merged, but only persist mobile after a valid phone-hint choice.
                Log.d(TAG, "Phone hint dismissed without a valid number")
            } catch (e: Exception) {
                Log.e(TAG, "Phone hint dismissal handling failed", e)
                Toast.makeText(this@MainActivity, R.string.profile_bootstrap_failed, Toast.LENGTH_SHORT).show()
            } finally {
                isProfileBootstrapInProgress = false
                pendingProfileBootstrap = null
                hasCheckedProfileBootstrap = false
            }
        }
    }

    private fun switchTab(tab: String) {
        activeTab = tab

        val tabRecents = findViewById<LinearLayout>(R.id.tabRecents)
        val tabBlockList = findViewById<LinearLayout>(R.id.tabBlockList)
        val tabWhiteList = findViewById<LinearLayout>(R.id.tabWhiteList)
        val tabProfile = findViewById<LinearLayout>(R.id.tabProfile)

        listOf(tabRecents, tabBlockList, tabWhiteList, tabProfile).forEach { t ->
            t.setBackgroundColor(Color.TRANSPARENT)
            val icon = t.getChildAt(0) as ImageView
            val label = t.getChildAt(1) as TextView
            icon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            label.setTextColor(Color.WHITE)
        }

        val activeView = when (tab) {
            "Recents" -> tabRecents
            "BlockList" -> tabBlockList
            "WhiteList" -> tabWhiteList
            else -> tabProfile
        }
        activeView.setBackgroundColor(getColor(R.color.brand_red))

        when (tab) {
            "BlockList" -> syncLookupCollectionIntoUi("blocked_numbers", LookupSource.BLOCKED)
            "WhiteList" -> syncLookupCollectionIntoUi("white_list_numbers", LookupSource.WHITELIST)
        }
        updateDisplayList()
    }

    private fun updateDisplayList() {
        val items = when (activeTab) {
            "BlockList" -> blockListItems
            "WhiteList" -> whiteListItems
            else -> callLogList
        }
        callLogAdapter.updateData(items)
    }

    private fun syncLookupCollectionIntoUi(
        subcollection: String,
        source: String,
        showCompletionToast: Boolean = false
    ) {
        val userId = auth.currentUser?.uid ?: return
        if (isListTabSyncInProgress) return
        isListTabSyncInProgress = true

        lifecycleScope.launch(Dispatchers.IO) {
            val entries = fetchLookupCollectionEntries(userId, subcollection, source)
            withContext(Dispatchers.Main) {
                applyLookupCollectionEntries(entries, source)
                if (showCompletionToast) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.refreshed_tab, getTabDisplayName(activeTab)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isListTabSyncInProgress = false
            }
        }
    }

    private suspend fun fetchLookupCollectionEntries(
        userId: String,
        subcollection: String,
        source: String
    ): List<CallLogItem> {
        val snapshots = FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .collection(subcollection)
            .get()
            .await()

        return snapshots.documents.mapNotNull { document ->
            val number = normalizeLookupNumber(
                document.getString("phoneNumber"),
                document.getString("number"),
                document.id
            ) ?: return@mapNotNull null

            val name = extractResolvedName(
                number,
                document.getString("primaryName"),
                document.getString("name")
            ) ?: number

            CallLogItem(
                name = name,
                number = number,
                type = "Unknown",
                time = LIST_TAB_PLACEHOLDER_TIME,
                lookupSource = source
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun applyLookupCollectionEntries(entries: List<CallLogItem>, source: String) {
        val previousNumbers = when (source) {
            LookupSource.BLOCKED -> blockListItems.map { it.number }.toSet()
            LookupSource.WHITELIST -> whiteListItems.map { it.number }.toSet()
            else -> emptySet()
        }

        when (source) {
            LookupSource.BLOCKED -> {
                blockListItems.clear()
                blockListItems.addAll(entries)
            }
            LookupSource.WHITELIST -> {
                whiteListItems.clear()
                whiteListItems.addAll(entries)
            }
        }

        val lookupMap = entries.associate { entry ->
            entry.number to RemoteLookupResult(entry.name, source)
        }
        val removedNumbers = previousNumbers - lookupMap.keys

        removedNumbers.forEach { number ->
            if (unknownLookupCache[number]?.source == source) {
                unknownLookupCache.remove(number)
            }
            searchedUnknownNumbers.remove(number)
        }

        unknownLookupCache.putAll(lookupMap)
        searchedUnknownNumbers.addAll(lookupMap.keys)
        saveSearchedUnknownNumbers()

        val updatedRecents = callLogList.map { item ->
            when {
                item.number in removedNumbers && item.lookupSource == source -> {
                    item.copy(
                        name = "",
                        isLookupInProgress = false,
                        lookupSource = null
                    )
                }
                else -> {
                    val resolved = lookupMap[item.number] ?: return@map item
                    item.copy(
                        name = resolved.name,
                        isLookupInProgress = false,
                        lookupSource = resolved.source
                    )
                }
            }
        }

        callLogList.clear()
        callLogList.addAll(updatedRecents)
        saveCurrentCacheSnapshot()

        updateDisplayList()
        rvCallLog.post { updateVisibleUnknownLookupTargets() }
    }

    private fun normalizeLookupNumber(vararg candidates: String?): String? {
        val raw = candidates.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) } ?: return null
        return PhoneNumberVariants.toIndianMobilePlus(raw)
            ?: PhoneNumberVariants.digitsOnly(raw).takeIf { it.isNotBlank() }
    }

    private fun getTabDisplayName(tab: String): String {
        return when (tab) {
            "BlockList" -> getString(R.string.tab_block_list)
            "WhiteList" -> getString(R.string.tab_white_list)
            else -> getString(R.string.tab_recents)
        }
    }

    private fun showAccountDialog() {
        val user = auth.currentUser
        val message = "${user?.displayName ?: getString(R.string.default_user_name)}\n${user?.email ?: getString(R.string.no_email_available)}"
        AlertDialog.Builder(this)
            .setTitle(R.string.account_status_label)
            .setMessage(message)
            .setPositiveButton(R.string.logout) { _, _ ->
                auth.signOut()
                navigateToLogin()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getMissingRuntimePermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions += Manifest.permission.READ_PHONE_NUMBERS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showAppSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_denied_permanent_title)
            .setMessage(R.string.permission_denied_permanent_message)
            .setPositiveButton(R.string.settings_label) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun syncContactsToDb(force: Boolean = false) {
        val userId = auth.currentUser?.uid ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        lifecycleScope.launch {
            try {
                ContactSyncManager.syncIfNeeded(this@MainActivity, userId, force)
            } catch (e: Exception) {
                FirestoreUi.handleFailure(this@MainActivity, e, "ContactSyncManager")
            }
        }
    }

    private fun handleCallStateChanged(state: Int) {
        val previousState = lastCallState
        lastCallState = state

        if ((previousState == TelephonyManager.CALL_STATE_RINGING || previousState == TelephonyManager.CALL_STATE_OFFHOOK) &&
            state == TelephonyManager.CALL_STATE_IDLE) {
            observerHandler.removeCallbacks(callEndedRefreshRunnable)
            observerHandler.postDelayed(callEndedRefreshRunnable, CALL_END_REFRESH_DELAY_MS)
        }
    }

    private fun registerCallStateListenerIfPossible() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback?.let {
                telephonyManager?.registerTelephonyCallback(mainExecutor, it)
            }
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback?.let {
                runCatching { telephonyManager?.unregisterTelephonyCallback(it) }
            }
        }
    }

    private fun loadCacheSnapshot(): CallLogCacheSnapshot? {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val json = prefs.getString(CACHE_KEY, null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<CallLogCacheSnapshot>() {}.type)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCacheSnapshot(snapshot: CallLogCacheSnapshot) {
        val prefs = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        prefs.edit().putString(CACHE_KEY, gson.toJson(snapshot)).apply()
    }

    override fun onDestroy() {
        if (::loadingAnimator.isInitialized) {
            loadingAnimator.cancel()
        }
        observerHandler.removeCallbacksAndMessages(null)
        unregisterCallStateListener()
        pollHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

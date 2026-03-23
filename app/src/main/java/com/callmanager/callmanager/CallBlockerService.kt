package com.callmanager.callmanager

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

@RequiresApi(Build.VERSION_CODES.N)
class CallBlockerService : CallScreeningService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle
        if (handle == null) {
            allowCall(callDetails)
            return
        }

        val phoneNumber = handle.schemeSpecificPart ?: ""
        val cleanNumber = phoneNumber.replace("[^0-9+]".toRegex(), "")
        
        Log.d("CallBlocker", "Screening call from: $cleanNumber")

        // 1. Check if number is in White List (VERIFIED) - ALWAYS ALLOW
        if (isNumberInWhitelist(cleanNumber)) {
            Log.d("CallBlocker", "Allowing $cleanNumber: Found in White List (Verified)")
            allowCall(callDetails)
            return
        }

        // 2. Check if "Block all unknown" is enabled
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val blockUnknown = prefs.getBoolean("block_all_unknown", false)
        
        if (blockUnknown) {
            if (!isNumberInContacts(cleanNumber)) {
                Log.d("CallBlocker", "Blocking $cleanNumber: Not in contacts (Block Unknown enabled)")
                blockCall(callDetails)
                return
            }
        }

        // 3. Immediate check against local cache (Personal Blocks)
        if (isNumberInLocalCache(cleanNumber)) {
            Log.d("CallBlocker", "Blocking $cleanNumber: Found in local cache")
            blockCall(callDetails)
            return
        }

        // 4. Fallback to Firestore check with a timeout
        serviceScope.launch {
            try {
                val isBlocked = withTimeoutOrNull(4000) {
                    checkIsBlocked(cleanNumber)
                } ?: false

                if (isBlocked) {
                    Log.d("CallBlocker", "Blocking $cleanNumber: Found in Firestore")
                    blockCall(callDetails)
                } else {
                    Log.d("CallBlocker", "Allowing call from $cleanNumber")
                    allowCall(callDetails)
                }
            } catch (e: Exception) {
                Log.e("CallBlocker", "Error screening call: ${e.message}")
                allowCall(callDetails)
            }
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

    private fun isNumberInWhitelist(phoneNumber: String): Boolean {
        val clean = normalizeNumber(phoneNumber)
        val prefs = getSharedPreferences("WhitelistCache", Context.MODE_PRIVATE)
        val cachedSet = prefs.getStringSet("white_set", emptySet()) ?: return false
        
        if (cachedSet.contains(clean)) return true
        
        // Match last 10 digits
        val last10 = clean.takeLast(10)
        return cachedSet.any { it.endsWith(last10) }
    }

    private fun isNumberInContacts(phoneNumber: String): Boolean {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isNumberInLocalCache(number: String): Boolean {
        val clean = normalizeNumber(number)
        val prefs = getSharedPreferences("BlockedNumbersCache", Context.MODE_PRIVATE)
        val cachedSet = prefs.getStringSet("blocked_set", emptySet()) ?: return false
        
        if (cachedSet.contains(clean)) return true
        
        val last10 = clean.takeLast(10)
        return cachedSet.any { it.endsWith(last10) }
    }

    private suspend fun checkIsBlocked(number: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        val clean = normalizeNumber(number)
        
        try {
            val localBlock = db.collection("users").document(currentUser.uid)
                .collection("blocked_numbers").document(clean).get().await()
            if (localBlock.exists()) return true
            
            // Check whitelist in firestore as well just in case - FIXED COLLECTION NAME
            val whiteDoc = db.collection("users").document(currentUser.uid)
                .collection("white_list_numbers").document(clean).get().await()
            if (whiteDoc.exists()) return false

            val last10 = clean.takeLast(10)
            
            // Personal Block check with suffix match
            val snapshots = db.collection("users").document(currentUser.uid)
                .collection("blocked_numbers")
                .get().await()
            
            if (snapshots.documents.any { it.id.endsWith(last10) }) return true
            
        } catch (e: Exception) {
            Log.e("CallBlocker", "Firestore personal block check failed: ${e.message}")
        }

        try {
            val globalSpam = db.collection("global_spam").document(clean).get().await()
            if (globalSpam.exists()) {
                return globalSpam.getBoolean("isVerifiedSpam") ?: false
            }
        } catch (e: Exception) {
            Log.e("CallBlocker", "Firestore global spam check failed: ${e.message}")
        }

        return false
    }

    private fun blockCall(callDetails: Call.Details) {
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("edge_glow_enabled", false) && Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(this, EdgeGlowService::class.java)
                startService(intent)
            } catch (e: Exception) {
                Log.e("CallBlocker", "Failed to start EdgeGlowService: ${e.message}")
            }
        }

        val responseBuilder = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            responseBuilder.setSilenceCall(true)
        }

        respondToCall(callDetails, responseBuilder.build())
    }

    private fun allowCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

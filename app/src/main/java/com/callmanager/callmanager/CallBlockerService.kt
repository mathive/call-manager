package com.callmanager.callmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class CallBlockerService : CallScreeningService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val firestore = FirebaseFirestore.getInstance()

    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val shouldBlockUnknown = prefs.getBoolean("block_all_unknown", false)
        val edgeGlowEnabled = prefs.getBoolean("edge_glow_enabled", false)
        val blockedNumbers = prefs.getStringSet("blocked_set", emptySet()) ?: emptySet()
        val allowedPrefixes = emptySet<String>()
        val blockedPrefixes = emptySet<String>()

        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else {
            true
        }

        if (isIncoming) {
            val handle = callDetails.handle
            if (handle != null && handle.scheme == "tel") {
                val phoneNumber = handle.schemeSpecificPart
                if (phoneNumber != null) {
                    val isBlockedByPrefix = blockedPrefixes.any { matchesPrefix(phoneNumber, it) }
                    val isAllowedByPrefix = allowedPrefixes.any { matchesPrefix(phoneNumber, it) }
                    val isBlockedManually = isNumberInSet(phoneNumber, blockedNumbers)
                    val isUnknown = !isNumberInContacts(phoneNumber)

                    if (isBlockedByPrefix || isBlockedManually || (shouldBlockUnknown && isUnknown && !isAllowedByPrefix)) {
                        blockCall(callDetails, edgeGlowEnabled)
                        return
                    }

                    checkGlobalSpamAndBlock(phoneNumber, callDetails, edgeGlowEnabled)
                    return
                }
            }
        }

        respondToCall(callDetails, CallResponse.Builder().build())
    }

    private fun isNumberInSet(number: String, set: Set<String>): Boolean {
        if (set.contains(number)) return true
        return set.any { PhoneNumberVariants.sameNumber(number, it) }
    }

    private fun checkGlobalSpamAndBlock(phoneNumber: String, callDetails: Call.Details, edgeGlowEnabled: Boolean) {
        val lookupIds = PhoneNumberVariants.buildFirestoreDocumentIds(phoneNumber)
        if (lookupIds.isEmpty()) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        serviceScope.launch {
            try {
                val directMatch = lookupIds.firstNotNullOfOrNull { lookupId ->
                    val snapshot = firestore.collection("global_spam")
                        .document(lookupId)
                        .get()
                        .await()
                    snapshot.takeIf { it.exists() }
                }

                val matchedDoc = directMatch
                    ?: firestore.collection("global_spam")
                        .whereIn("phoneNumber", lookupIds.take(10))
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                    ?: firestore.collection("global_spam")
                        .whereIn("number", lookupIds.take(10))
                        .get()
                        .await()
                        .documents
                        .firstOrNull()

                if (matchedDoc?.exists() == true) {
                    val isVerifiedSpam = matchedDoc.getBoolean("isVerifiedSpam") ?: false
                    if (isVerifiedSpam) {
                        Log.d("CallBlockerService", "Blocking global verified spam: ${matchedDoc.id}")
                        withContext(Dispatchers.Main) {
                            blockCall(callDetails, edgeGlowEnabled)
                        }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.e("CallBlockerService", "Global spam check failed", e)
            }

            withContext(Dispatchers.Main) {
                respondToCall(callDetails, CallResponse.Builder().build())
            }
        }
    }

    private fun isNumberInContacts(phoneNumber: String): Boolean {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        return try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> cursor.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun matchesPrefix(number: String, prefix: String): Boolean {
        val cleanNumber = PhoneNumberVariants.digitsOnly(number)
        val cleanPrefix = PhoneNumberVariants.digitsOnly(prefix)
        if (cleanPrefix.isEmpty()) return false
        if (cleanNumber.startsWith(cleanPrefix)) return true
        if (cleanNumber.startsWith("0") && cleanNumber.substring(1).startsWith(cleanPrefix)) return true
        val localPart = PhoneNumberVariants.toLocalTenDigits(number)
        if (!localPart.isNullOrBlank()) {
            if (localPart.startsWith(cleanPrefix)) return true
        }
        return false
    }

    private fun blockCall(callDetails: Call.Details, edgeGlowEnabled: Boolean) {
        val responseBuilder = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            responseBuilder.setSilenceCall(true)
        }

        if (edgeGlowEnabled) {
            startService(Intent(this, EdgeGlowService::class.java))
        }

        respondToCall(callDetails, responseBuilder.build())
    }
}

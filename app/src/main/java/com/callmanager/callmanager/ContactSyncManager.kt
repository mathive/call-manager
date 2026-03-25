package com.callmanager.callmanager

import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.edit
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

object ContactSyncManager {

    private const val TAG = "ContactSyncManager"
    private const val PREFS_NAME = "contact_sync_state"
    private const val SNAPSHOT_KEY = "local_contact_snapshot"
    private const val PAUSE_UNTIL_KEY = "pause_until"
    private const val PAUSE_MESSAGE_KEY = "pause_message"
    private const val BATCH_WRITE_LIMIT = 400
    private const val QUOTA_PAUSE_MS = 30 * 60 * 1000L
    private const val SERVICE_PAUSE_MS = 10 * 60 * 1000L
    private const val REMOTE_READ_TIMEOUT_MS = 20_000L
    private const val BATCH_COMMIT_TIMEOUT_MS = 20_000L
    private val gson = Gson()
    private val syncMutex = Mutex()

    suspend fun syncIfNeeded(context: Context, userId: String, force: Boolean = false) {
        syncMutex.withLock {
            val appContext = context.applicationContext
            val pauseState = loadPauseState(context)
            if (!force && pauseState != null && System.currentTimeMillis() < pauseState.pauseUntilMillis) {
                SyncNotificationHelper.showPaused(appContext, pauseState.message)
                return
            }

            val firestore = FirebaseFirestore.getInstance()
            if (pauseState != null && System.currentTimeMillis() >= pauseState.pauseUntilMillis) {
                clearPauseState(context)
                restoreFirestoreNetwork(firestore)
            }

            val localContacts = withContext(Dispatchers.IO) { readLocalContacts(context) }
            val localSnapshot = loadLocalSnapshot(context)

            if (!force && localContacts == localSnapshot) {
                return
            }

            if (!force && isDeletionOnlyChange(localSnapshot, localContacts)) {
                saveLocalSnapshot(context, localContacts)
                return
            }

            SyncNotificationHelper.showRunning(appContext)

            try {
                val remoteHashes = fetchRemoteHashes(firestore, userId)
                val changedContacts = localContacts.values.filter { contact ->
                    remoteHashes[contact.number] != contact.hash
                }

                if (changedContacts.isNotEmpty()) {
                    var syncedCount = 0
                    SyncNotificationHelper.showRunningProgress(appContext, syncedCount, changedContacts.size)
                    changedContacts.chunked(BATCH_WRITE_LIMIT).forEach { chunk ->
                        val batch = firestore.batch()
                        chunk.forEach { contact ->
                            val doc = firestore.collection("users")
                                .document(userId)
                                .collection("contacts")
                                .document(contact.number)

                            val payload = mutableMapOf<String, Any>(
                                "name" to contact.name,
                                "number" to contact.number,
                                "hash" to contact.hash,
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "lastSeenLocallyAt" to FieldValue.serverTimestamp()
                            )

                            if (!remoteHashes.containsKey(contact.number)) {
                                payload["createdAt"] = FieldValue.serverTimestamp()
                            }

                            batch.set(doc, payload, SetOptions.merge())
                        }
                        withTimeout(BATCH_COMMIT_TIMEOUT_MS) {
                            batch.commit().await()
                        }
                        syncedCount += chunk.size
                        SyncNotificationHelper.showRunningProgress(appContext, syncedCount, changedContacts.size)
                    }
                }

                saveLocalSnapshot(context, localContacts)
                clearPauseState(context)
                restoreFirestoreNetwork(firestore)
                SyncNotificationHelper.showCompleted(appContext)
            } catch (e: Exception) {
                val firestoreCode = extractFirestoreCode(e)
                Log.e(TAG, "Contact sync failed with code=$firestoreCode", e)
                if (firestoreCode == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ||
                    firestoreCode == FirebaseFirestoreException.Code.UNAVAILABLE ||
                    firestoreCode == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ||
                    e is TimeoutCancellationException) {
                    val pauseMessage = buildPauseMessage(context, e)
                    pauseSync(
                        context = context,
                        firestore = firestore,
                        message = pauseMessage,
                        pauseUntilMillis = System.currentTimeMillis() + pauseDurationFor(e)
                    )
                    return
                }
                SyncNotificationHelper.showPaused(appContext, buildPauseMessage(context, e))
                throw e
            }
        }
    }

    private fun buildPauseMessage(context: Context, error: Exception): String {
        return when (extractFirestoreCode(error)) {
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                context.getString(R.string.sync_notification_message_paused_quota)
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                context.getString(R.string.sync_notification_message_paused_maintenance)
            else ->
                context.getString(R.string.sync_notification_message_paused_generic)
        }
    }

    private fun pauseDurationFor(error: Exception): Long {
        return when (extractFirestoreCode(error)) {
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> QUOTA_PAUSE_MS
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> SERVICE_PAUSE_MS
            else -> SERVICE_PAUSE_MS
        }
    }

    private fun extractFirestoreCode(error: Throwable?): FirebaseFirestoreException.Code? {
        var current = error
        while (current != null) {
            val firestoreCode = (current as? FirebaseFirestoreException)?.code
            if (firestoreCode != null) {
                return firestoreCode
            }

            val message = current.message.orEmpty()
            if ("RESOURCE_EXHAUSTED" in message || "Quota exceeded" in message) {
                return FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
            }
            if ("DEADLINE_EXCEEDED" in message) {
                return FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
            }
            if ("UNAVAILABLE" in message) {
                return FirebaseFirestoreException.Code.UNAVAILABLE
            }

            current = current.cause
        }
        return null
    }

    private suspend fun fetchRemoteHashes(
        firestore: FirebaseFirestore,
        userId: String
    ): Map<String, String> {
        val snapshot = withTimeout(REMOTE_READ_TIMEOUT_MS) {
            firestore.collection("users")
                .document(userId)
                .collection("contacts")
                .get()
                .await()
        }

        return snapshot.documents.associate { doc ->
            doc.id to (doc.getString("hash") ?: "")
        }
    }

    private suspend fun pauseSync(
        context: Context,
        firestore: FirebaseFirestore,
        message: String,
        pauseUntilMillis: Long
    ) {
        savePauseState(
            context = context,
            message = message,
            pauseUntilMillis = pauseUntilMillis
        )
        try {
            firestore.disableNetwork().await()
        } catch (disableError: Exception) {
            Log.w(TAG, "Failed to disable Firestore network during pause", disableError)
        }
        SyncNotificationHelper.showPaused(context.applicationContext, message)
    }

    private suspend fun restoreFirestoreNetwork(firestore: FirebaseFirestore) {
        try {
            firestore.enableNetwork().await()
        } catch (enableError: Exception) {
            Log.w(TAG, "Failed to re-enable Firestore network", enableError)
        }
    }

    private fun readLocalContacts(context: Context): Map<String, SyncedContact> {
        val contacts = linkedMapOf<String, SyncedContact>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            val contactIdIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val contactId = if (contactIdIdx >= 0) it.getString(contactIdIdx) ?: "" else ""
                val rawNumber = if (numberIdx >= 0) it.getString(numberIdx) ?: "" else ""
                val normalizedNumber = normalizeNumber(rawNumber)
                if (!isValidMobileNumber(normalizedNumber)) continue

                val rawName = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                val finalName = rawName.trim()
                if (finalName.isBlank()) continue
                if (isEmptyBusinessCard(context, contactId)) continue

                val contact = SyncedContact(
                    name = finalName,
                    number = normalizedNumber,
                    hash = buildHash(finalName, normalizedNumber)
                )

                val existing = contacts[normalizedNumber]
                if (existing == null || shouldReplace(existing, contact)) {
                    contacts[normalizedNumber] = contact
                }
            }
        }

        return contacts
    }

    private fun normalizeNumber(number: String): String {
        return PhoneNumberVariants.toIndianMobileDigits(number)
            ?: PhoneNumberVariants.digitsOnly(number)
    }

    private fun buildHash(name: String, number: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$name|$number".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isValidMobileNumber(number: String): Boolean {
        return PhoneNumberVariants.isValidIndianMobile(number)
    }

    private fun shouldReplace(existing: SyncedContact, candidate: SyncedContact): Boolean {
        if (existing.name.isBlank()) return true
        return candidate.name.length > existing.name.length
    }

    private fun isEmptyBusinessCard(context: Context, contactId: String): Boolean {
        if (contactId.isBlank()) return false

        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE),
            "${ContactsContract.Data.CONTACT_ID}=?",
            arrayOf(contactId),
            null
        )

        cursor?.use {
            var meaningfulFieldCount = 0
            while (it.moveToNext()) {
                val mimeType = it.getString(0) ?: continue
                if (mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ||
                    mimeType == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    meaningfulFieldCount++
                }
                if (meaningfulFieldCount > 1) {
                    return false
                }
            }
            return meaningfulFieldCount <= 1
        }

        return false
    }

    private fun loadLocalSnapshot(context: Context): Map<String, SyncedContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(SNAPSHOT_KEY, null) ?: return emptyMap()
        return try {
            gson.fromJson(json, object : TypeToken<Map<String, SyncedContact>>() {}.type)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveLocalSnapshot(context: Context, snapshot: Map<String, SyncedContact>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(SNAPSHOT_KEY, gson.toJson(snapshot))
        }
    }

    private fun loadPauseState(context: Context): PauseState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pauseUntil = prefs.getLong(PAUSE_UNTIL_KEY, 0L)
        val message = prefs.getString(PAUSE_MESSAGE_KEY, null) ?: return null
        if (pauseUntil <= 0L) return null
        return PauseState(pauseUntil, message)
    }

    private fun savePauseState(context: Context, message: String, pauseUntilMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(PAUSE_UNTIL_KEY, pauseUntilMillis)
            putString(PAUSE_MESSAGE_KEY, message)
        }
    }

    private fun clearPauseState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(PAUSE_UNTIL_KEY)
            remove(PAUSE_MESSAGE_KEY)
        }
    }

    private data class SyncedContact(
        val name: String,
        val number: String,
        val hash: String
    )

    private data class PauseState(
        val pauseUntilMillis: Long,
        val message: String
    )

    private fun isDeletionOnlyChange(
        previous: Map<String, SyncedContact>,
        current: Map<String, SyncedContact>
    ): Boolean {
        if (previous.isEmpty()) return false
        if (current.size >= previous.size) return false

        return current.all { (number, contact) ->
            previous[number] == contact
        }
    }
}

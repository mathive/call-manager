package com.callmanager.callmanager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class ContactSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: return Result.failure()
        val db = FirebaseFirestore.getInstance()

        // 1. Check Permissions
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            showSyncNotification("Sync paused: Permission missing", "Please grant contacts permission to resume sync.")
            return Result.failure()
        }

        return try {
            // 2. Fetch and Validate Contacts
            val contacts = fetchLocalContacts()
            if (contacts.isEmpty()) return Result.success()

            // 3. Change Detection (MD5 Hash)
            val currentHash = calculateHash(contacts)
            val prefs = applicationContext.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
            val lastHash = prefs.getString("last_contact_hash", "")

            if (currentHash == lastHash) {
                return Result.success() // No changes found, skip upload to save data/battery
            }

            // 4. Safe Batch Upload (Handling Firestore 500-limit)
            val userContactsRef = db.collection("users").document(uid).collection("contacts")
            
            // Split contacts into chunks of 500 to avoid Firestore batch limits
            val chunks = contacts.chunked(500)
            
            for (chunk in chunks) {
                val batch = db.batch()
                for (contact in chunk) {
                    val number = contact["number"] as String
                    val docId = number.replace("[^0-9+]".toRegex(), "")
                    if (docId.isNotEmpty()) {
                        val docRef = userContactsRef.document(docId)
                        batch.set(docRef, contact)
                    }
                }
                batch.commit().await()
            }
            
            // 5. Success Handling
            prefs.edit().putString("last_contact_hash", currentHash).apply()
            
            // Clear any error notifications
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1001)
            
            Result.success()
        } catch (e: Exception) {
            // 6. Error Handling with Notification
            showSyncNotification("Sync paused", "We'll resume syncing your contacts soon.")
            Result.retry() // WorkManager will automatically retry later
        }
    }

    private fun fetchLocalContacts(): List<Map<String, Any>> {
        val contactList = mutableListOf<Map<String, Any>>()
        val contentResolver = applicationContext.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: "Unknown"
                val number = cursor.getString(numIdx) ?: ""
                
                // Only include contacts with valid phone numbers
                if (number.isNotBlank() && isValidPhoneNumber(number)) {
                    contactList.add(mapOf(
                        "name" to name.trim(),
                        "number" to number.trim()
                    ))
                }
            }
        }
        // Deduplicate by number and sort for consistent hashing
        return contactList.distinctBy { it["number"] }.sortedBy { it["number"] as String }
    }

    private fun isValidPhoneNumber(number: String): Boolean {
        // Simple check: must have at least 7 digits to be considered a real phone number
        val digitsOnly = number.replace("[^0-9]".toRegex(), "")
        return digitsOnly.length >= 7
    }

    private fun calculateHash(contacts: List<Map<String, Any>>): String {
        val input = contacts.joinToString("|") { "${it["name"]}:${it["number"]}" }
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun showSyncNotification(title: String, message: String) {
        val channelId = "sync_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sync Notifications", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_history) // Using existing history icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}

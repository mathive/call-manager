package com.callmanager.callmanager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

data class PendingProfileBootstrap(
    val userId: String,
    val email: String,
    val name: String,
    val role: String,
    val numbers: List<String>,
    val requiresSelection: Boolean,
    val simOptions: List<SimSelectionOption>
)

data class SimSelectionOption(
    val label: String,
    val number: String?
)

object UserProfileBootstrapper {
    private const val TAG = "UserProfileBootstrapper"

    suspend fun getPendingBootstrap(context: Context, user: FirebaseUser): PendingProfileBootstrap? {
        val document = FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.uid)
            .get()
            .await()

        val currentMobileNumber = normalizePhoneNumber(
            document.getString("mobile")
            .orEmpty()
            .ifBlank { document.getString("mobileNumber").orEmpty() }
            .trim()
        )
        if (!currentMobileNumber.isNullOrBlank()) {
            return null
        }

        val email = user.email.orEmpty().trim()
        if (email.isBlank()) {
            return null
        }

        val deviceNumbers = getDevicePhoneNumbers(context)

        return PendingProfileBootstrap(
            userId = user.uid,
            email = email,
            name = user.displayName?.trim().orEmpty().ifBlank { deriveNameFromEmail(email) },
            role = document.getString("role").orEmpty().ifBlank { "Guest" },
            numbers = deviceNumbers.numbers,
            requiresSelection = deviceNumbers.requiresSelection,
            simOptions = deviceNumbers.simOptions
        )
    }

    suspend fun applyBootstrap(bootstrap: PendingProfileBootstrap, selectedNumber: String? = null) {
        val firestore = FirebaseFirestore.getInstance()
        val normalizedSelectedNumber = normalizePhoneNumber(selectedNumber)
        Log.d(TAG, "applyBootstrap rawSelectedNumber=$selectedNumber normalizedSelectedNumber=$normalizedSelectedNumber userId=${bootstrap.userId}")
        val payload = hashMapOf<String, Any>(
            "email" to bootstrap.email,
            "name" to bootstrap.name,
            "role" to bootstrap.role,
            "isVerified" to true
        )
        if (!normalizedSelectedNumber.isNullOrBlank()) {
            payload["mobile"] = normalizedSelectedNumber
        }

        firestore
            .collection("users")
            .document(bootstrap.userId)
            .set(payload, SetOptions.merge())
            .await()
        Log.d(TAG, "users profile merged successfully for userId=${bootstrap.userId}")

        if (!normalizedSelectedNumber.isNullOrBlank()) {
            firestore
                .collection("global_verifed")
                .document(normalizedSelectedNumber)
                .set(
                    hashMapOf(
                        "phoneNumber" to normalizedSelectedNumber,
                        "primaryName" to bootstrap.name,
                        "source" to "profile_info",
                        "uid" to bootstrap.userId,
                        "updated_at" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
            Log.d(TAG, "global_verifed updated for number=$normalizedSelectedNumber")
        } else if (!selectedNumber.isNullOrBlank()) {
            Log.w(TAG, "Skipping mobile/global_verifed write because selected number is invalid: $selectedNumber")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDevicePhoneNumbers(context: Context): DevicePhoneNumbers {
        val subscriptionNumbers = linkedSetOf<String>()
        val fallbackNumbers = linkedSetOf<String>()
        val simOptions = mutableListOf<SimSelectionOption>()
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        var activeSubscriptionCount = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            val activeSubscriptions = subscriptionManager?.activeSubscriptionInfoList.orEmpty()
            activeSubscriptionCount = activeSubscriptions.size
            activeSubscriptions.forEachIndexed { index, info ->
                val normalizedInfoNumber = normalizePhoneNumber(info.number)
                normalizedInfoNumber?.let(subscriptionNumbers::add)
                val normalizedLine1Number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    normalizePhoneNumber(
                        telephonyManager?.createForSubscriptionId(info.subscriptionId)?.line1Number
                    )
                } else {
                    null
                }
                normalizedLine1Number?.let(subscriptionNumbers::add)

                val chosenNumber = normalizedInfoNumber ?: normalizedLine1Number
                simOptions += SimSelectionOption(
                    label = "SIM ${index + 1}${chosenNumber?.let { " ($it)" } ?: ""}",
                    number = chosenNumber
                )
            }
        } else {
            activeSubscriptionCount = 1
        }

        normalizePhoneNumber(telephonyManager?.line1Number)?.let(fallbackNumbers::add)

        val finalNumbers = when {
            subscriptionNumbers.isNotEmpty() -> subscriptionNumbers.toList()
            fallbackNumbers.isNotEmpty() -> fallbackNumbers.toList()
            else -> emptyList()
        }

        if (simOptions.isEmpty() && finalNumbers.isNotEmpty()) {
            simOptions += SimSelectionOption(
                label = finalNumbers.first(),
                number = finalNumbers.first()
            )
        }

        val requiresSelection = activeSubscriptionCount > 1
        return DevicePhoneNumbers(
            numbers = finalNumbers,
            requiresSelection = requiresSelection,
            simOptions = simOptions
        )
    }

    private fun normalizePhoneNumber(rawNumber: String?): String? {
        return PhoneNumberVariants.toIndianMobilePlus(rawNumber)
    }

    private fun deriveNameFromEmail(email: String): String {
        val localPart = email.substringBefore("@")
        val words = localPart
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return "Guest"

        return words.joinToString(" ") { word ->
            word.lowercase(Locale.getDefault()).replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
            }
        }
    }

    private data class DevicePhoneNumbers(
        val numbers: List<String>,
        val requiresSelection: Boolean,
        val simOptions: List<SimSelectionOption>
    )
}

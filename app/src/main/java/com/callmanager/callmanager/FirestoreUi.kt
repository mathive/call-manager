package com.callmanager.callmanager

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.CancellationException

object FirestoreUi {
    private const val PREFS_NAME = "FirestoreUi"
    private const val KEY_PENDING_MESSAGE = "pending_message"

    fun handleFailure(context: Context, error: Exception, tag: String) {
        if (isCancellation(error)) {
            Log.d(tag, "Skipping Firestore failure toast because the job was cancelled", error)
            return
        }

        Log.e(tag, "Firestore operation failed", error)

        val code = buildErrorCode(error)
        val message = "System is busy. Please try later. ($code)"

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PENDING_MESSAGE, message)
        }

        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun showPendingMessageIfAny(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_PENDING_MESSAGE, null) ?: return
        prefs.edit { remove(KEY_PENDING_MESSAGE) }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun buildErrorCode(error: Exception): String {
        val firestoreCode = extractFirestoreCode(error)
        return when (firestoreCode) {
            FirebaseFirestoreException.Code.CANCELLED -> "CM-E101"
            FirebaseFirestoreException.Code.UNKNOWN -> "CM-E102"
            FirebaseFirestoreException.Code.INVALID_ARGUMENT -> "CM-E103"
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> "CM-E104"
            FirebaseFirestoreException.Code.NOT_FOUND -> "CM-E105"
            FirebaseFirestoreException.Code.ALREADY_EXISTS -> "CM-E106"
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> "CM-E107"
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> "CM-E108"
            FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "CM-E109"
            FirebaseFirestoreException.Code.ABORTED -> "CM-E110"
            FirebaseFirestoreException.Code.OUT_OF_RANGE -> "CM-E111"
            FirebaseFirestoreException.Code.UNIMPLEMENTED -> "CM-E112"
            FirebaseFirestoreException.Code.INTERNAL -> "CM-E113"
            FirebaseFirestoreException.Code.UNAVAILABLE -> "CM-E114"
            FirebaseFirestoreException.Code.DATA_LOSS -> "CM-E115"
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> "CM-E116"
            else -> "CM-E000"
        }
    }

    private fun extractFirestoreCode(error: Throwable?): FirebaseFirestoreException.Code? {
        var current = error
        while (current != null) {
            val firestoreCode = (current as? FirebaseFirestoreException)?.code
            if (firestoreCode != null) {
                return firestoreCode
            }
            current = current.cause
        }
        return null
    }

    private fun isCancellation(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is CancellationException) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

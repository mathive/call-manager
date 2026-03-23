package com.callmanager.callmanager

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.google.firebase.firestore.FirebaseFirestoreException

object FirestoreUi {
    private const val PREFS_NAME = "FirestoreUi"
    private const val KEY_PENDING_MESSAGE = "pending_message"

    fun handleFailure(context: Context, error: Exception, tag: String) {
        Log.e(tag, "Firestore operation failed", error)

        val message = when ((error as? FirebaseFirestoreException)?.code) {
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                "System is under maintenance. Please try later."
            else ->
                "System is busy. Please try later."
        }

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
}

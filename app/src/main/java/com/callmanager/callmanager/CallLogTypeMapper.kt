package com.callmanager.callmanager

import android.provider.CallLog

data class CallLogPresentation(
    val label: String,
    val iconRes: Int
)

object CallLogTypeMapper {
    fun toPresentation(type: Int): CallLogPresentation {
        return when (type) {
            CallLog.Calls.OUTGOING_TYPE -> CallLogPresentation("Outgoing", R.drawable.ic_call_outgoing)
            CallLog.Calls.INCOMING_TYPE,
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallLogPresentation("Incoming", R.drawable.ic_call_incoming)
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE -> CallLogPresentation("Missed", R.drawable.ic_call_missed)
            CallLog.Calls.VOICEMAIL_TYPE -> CallLogPresentation("Incoming", R.drawable.ic_call_incoming)
            else -> CallLogPresentation("Unknown", R.drawable.ic_call_missed)
        }
    }
}

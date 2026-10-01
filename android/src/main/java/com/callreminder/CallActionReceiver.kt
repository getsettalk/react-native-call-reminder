package com.callreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Decline button, notification dismissal and the timeout alarm. Not exported:
 * only our own PendingIntents can reach it. Work is a few small synchronous
 * writes, well within a receiver's budget.
 */
class CallActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val callId = intent.getStringExtra(CallIntents.EXTRA_CALL_ID) ?: return
    when (intent.action) {
      CallIntents.ACTION_DECLINE -> CallController.decline(context, callId, null)
      CallIntents.ACTION_DISMISSED -> CallController.onDismissed(context, callId)
      CallIntents.ACTION_TIMEOUT -> CallController.onTimeout(context, callId)
    }
  }
}

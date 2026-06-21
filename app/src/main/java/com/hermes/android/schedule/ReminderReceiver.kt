package com.hermes.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a mirrored cron job's exact alarm goes off. Posts the reminder
 * notification and marks the entry fired so a subsequent reconcile won't re-arm
 * this occurrence. Runs even if the app was swiped away and Hermes/Termux is
 * dead — that's the reliability win over the server-side cron ticker.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(ReminderScheduler.EXTRA_JOB_ID) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Reminder"
        val store = MirroredTaskStore(context)
        // Reuse the job's stable code (set when armed) so the notification id
        // matches its alarm; fall back to the hashCode only for a stale alarm.
        val id = store.get(jobId)?.requestCode ?: jobId.hashCode()
        Notifications.ensureChannel(context)
        Notifications.post(context, id, title)
        store.markFired(jobId)
    }
}

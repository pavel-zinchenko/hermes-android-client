package com.hermes.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms persisted reminder alarms after a reboot. Android clears AlarmManager
 * on restart, so without this every mirrored reminder would be silently lost.
 * [ReminderScheduler.rearmPersisted] reads the local store (no network), so this
 * works even if Hermes is unreachable at boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Notifications.ensureChannel(context)
        ReminderScheduler(context).rearmPersisted()
    }
}

package com.hermes.android.schedule

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * One cron job mirrored into a local Android alarm. Persisted so alarms can be
 * re-armed after the app process dies or the device reboots (Android clears
 * AlarmManager on reboot), and so [ReminderScheduler.reconcile] can diff the
 * desired server state against what's already scheduled.
 */
data class MirroredReminder(
    val jobId: String,
    /** Absolute fire time, epoch millis. */
    val fireAtMs: Long,
    /** Notification title (the job name / reminder text). */
    val title: String,
    /**
     * Stable per-job request code for this job's alarm [android.app.PendingIntent]
     * and notification id. Allocated from a monotonic counter (see
     * [MirroredTaskStore.nextRequestCode]) rather than `jobId.hashCode()` so two
     * jobs can never collide and silently overwrite each other's alarm.
     */
    val requestCode: Int,
    /** True once the alarm has fired and posted its notification (don't re-arm). */
    val fired: Boolean = false,
)

/**
 * Synchronous, process-local persistence for [MirroredReminder]s, backed by
 * SharedPreferences (a single JSON blob). SharedPreferences — rather than the
 * DataStore used for settings — because [ReminderReceiver] and [BootReceiver]
 * read/write this from `BroadcastReceiver.onReceive`, where a synchronous API
 * avoids juggling coroutines in a short-lived receiver.
 */
class MirroredTaskStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("hermes_reminders", Context.MODE_PRIVATE)

    fun all(): List<MirroredReminder> = synchronized(lock) {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        runCatching { gson.fromJson<List<MirroredReminder>>(json, LIST_TYPE) }
            .getOrNull().orEmpty()
    }

    fun get(jobId: String): MirroredReminder? = all().firstOrNull { it.jobId == jobId }

    fun put(entry: MirroredReminder) = synchronized(lock) {
        val next = all().filterNot { it.jobId == entry.jobId } + entry
        write(next)
    }

    fun remove(jobId: String) = synchronized(lock) {
        write(all().filterNot { it.jobId == jobId })
    }

    /** Marks the entry fired so a later reconcile won't re-arm this occurrence. */
    fun markFired(jobId: String) = synchronized(lock) {
        write(all().map { if (it.jobId == jobId) it.copy(fired = true) else it })
    }

    /**
     * Allocates the next never-before-used request code from a monotonic counter.
     * Stable codes (kept on the [MirroredReminder]) avoid the hashCode-collision
     * risk of `jobId.hashCode()`, where two jobs could share one PendingIntent.
     */
    fun nextRequestCode(): Int = synchronized(lock) {
        val next = prefs.getInt(KEY_SEQ, BASE_CODE) + 1
        prefs.edit().putInt(KEY_SEQ, next).apply()
        next
    }

    private fun write(entries: List<MirroredReminder>) {
        prefs.edit().putString(KEY, gson.toJson(entries)).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val KEY_SEQ = "request_code_seq"
        const val BASE_CODE = 1000
        val lock = Any()
        val gson = Gson()
        val LIST_TYPE = object : TypeToken<List<MirroredReminder>>() {}.type
    }
}

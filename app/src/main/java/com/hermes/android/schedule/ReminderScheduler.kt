package com.hermes.android.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hermes.android.data.model.ScheduledTask

/**
 * Mirrors Hermes cron jobs into exact Android alarms so reminders fire even when
 * the Hermes/Termux process is suspended (the whole point: Doze freezes the
 * in-process cron ticker, but an OS alarm wakes the device regardless).
 *
 * The mechanism is pure Android — [reconcile]/[cancel] take desired state from
 * the caller (the repository supplies the task list); this class never touches
 * the network. Each job maps to one [PendingIntent] keyed by a stable, collision-
 * free request code (allocated by [MirroredTaskStore.nextRequestCode] and kept on
 * the persisted entry) targeting [ReminderReceiver].
 */
class ReminderScheduler(private val context: Context) {

    private val store = MirroredTaskStore(context)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Converges scheduled alarms toward [tasks] (the active local jobs from the
     * server): arm new ones, re-arm jobs whose fire time changed (recurring jobs
     * advancing to their next occurrence), cancel alarms for jobs that are gone,
     * and leave already-fired occurrences alone so a past-due lingering job isn't
     * re-notified.
     */
    fun reconcile(tasks: List<ScheduledTask>) {
        val desired = tasks.associateBy { it.id }

        // Drop alarms for jobs the server no longer reports as active.
        for (entry in store.all()) {
            if (!desired.containsKey(entry.jobId)) {
                cancelAlarm(entry.requestCode)
                store.remove(entry.jobId)
            }
        }

        for (task in tasks) {
            val existing = store.get(task.id)
            val sameOccurrence = existing != null && existing.fireAtMs == task.nextRunAtMs
            if (sameOccurrence && existing!!.fired) continue // already fired this occurrence

            // Reuse a job's existing code so re-arms hit the same alarm; allocate a
            // fresh one only for a job we haven't mirrored before.
            val code = existing?.requestCode ?: store.nextRequestCode()
            armAlarm(code, task.id, task.nextRunAtMs, task.name)
            if (!sameOccurrence) {
                // New job or advanced to a new occurrence: (re)record, fired = false.
                store.put(MirroredReminder(task.id, task.nextRunAtMs, task.name, code, fired = false))
            }
        }
    }

    /** Cancels a job's alarm and forgets it (used when the user deletes a task). */
    fun cancel(jobId: String) {
        store.get(jobId)?.let { cancelAlarm(it.requestCode) }
        store.remove(jobId)
    }

    /**
     * Arms (or re-arms) a single task without touching other mirrored alarms.
     * Unlike [reconcile] this does not diff against the full desired set, so it's
     * safe to call for one task (e.g. to undo a failed delete).
     */
    fun arm(task: ScheduledTask) {
        val code = store.get(task.id)?.requestCode ?: store.nextRequestCode()
        armAlarm(code, task.id, task.nextRunAtMs, task.name)
        store.put(MirroredReminder(task.id, task.nextRunAtMs, task.name, code, fired = false))
    }

    /**
     * Re-arms every not-yet-fired persisted alarm. Android wipes AlarmManager on
     * reboot, so [BootReceiver] calls this after `BOOT_COMPLETED`. Alarms whose
     * time already passed while powered off fire (almost) immediately.
     */
    fun rearmPersisted() {
        store.all().filterNot { it.fired }
            .forEach { armAlarm(it.requestCode, it.jobId, it.fireAtMs, it.title) }
    }

    private fun armAlarm(requestCode: Int, jobId: String, fireAtMs: Long, title: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_TITLE, title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Exact alarms need user consent on API 31+; fall back to an inexact (but
        // still Doze-piercing) alarm when not granted so reminders still fire,
        // just less precisely.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pending)
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_TITLE = "title"
    }
}

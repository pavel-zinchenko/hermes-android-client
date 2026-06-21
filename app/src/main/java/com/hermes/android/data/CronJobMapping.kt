package com.hermes.android.data

import com.hermes.android.data.dto.CronJobDto
import com.hermes.android.data.model.ScheduledTask
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pure mapping from the cron-job wire shape to the app's [ScheduledTask] model,
 * plus the "which jobs do we mirror" filter. Kept free of Android/network deps so
 * it can be unit-tested directly (see CronJobMappingTest).
 */

/**
 * Parses a Hermes ISO-8601 UTC timestamp (e.g. `2026-02-03T14:30:00+00:00` or
 * `...Z`) to epoch millis, or null if absent/unparseable. [OffsetDateTime]
 * accepts both the `Z` and `+00:00` offset forms; [Instant] is the fallback.
 */
fun parseIso8601Millis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(iso).toEpochMilli() }
        .getOrNull()
}

/**
 * Maps cron jobs to the active local reminders the app should mirror: `enabled`,
 * not `completed`, delivered locally (`deliver == "local"`), with a parseable
 * next fire still in the future. `deliver == "local"` is the "this is a phone
 * reminder" marker — it keeps agent jobs that deliver to a chat platform
 * (Telegram/etc.) from turning into phone notifications. Sorted by fire time.
 */
fun List<CronJobDto>.toScheduledTasks(nowMs: Long = System.currentTimeMillis()): List<ScheduledTask> =
    asSequence()
        .filter { it.enabled }
        .filter { it.deliver.equals("local", ignoreCase = true) }
        .filter { !it.state.equals("completed", ignoreCase = true) }
        .mapNotNull { dto ->
            val fireMs = parseIso8601Millis(dto.nextRunAt) ?: return@mapNotNull null
            if (fireMs <= nowMs) return@mapNotNull null
            val kind = dto.schedule?.kind ?: "once"
            ScheduledTask(
                id = dto.id,
                name = dto.name?.takeIf { it.isNotBlank() }
                    ?: dto.prompt?.takeIf { it.isNotBlank() }
                    ?: "Reminder",
                scheduleDisplay = dto.schedule?.display?.takeIf { it.isNotBlank() } ?: kind,
                kind = kind,
                nextRunAtMs = fireMs,
                recurring = !kind.equals("once", ignoreCase = true),
            )
        }
        .sortedBy { it.nextRunAtMs }
        .toList()

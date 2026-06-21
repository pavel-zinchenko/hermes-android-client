package com.hermes.android.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hermes.android.data.dto.CronJobDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cron-job wire decoding (GET /api/cron/jobs, shaped by
 * hermes-agent cron/jobs.py) and the "which jobs do we mirror" filter:
 * only active, local-delivery jobs with a future next_run_at.
 */
class CronJobMappingTest {

    private val gson = Gson()
    private val listType = object : TypeToken<List<CronJobDto>>() {}.type

    // A fixed "now" so future/past filtering is deterministic (derived via the
    // same parser under test to avoid a hand-computed epoch constant).
    private val now = parseIso8601Millis("2026-06-21T12:00:00Z")!!

    private fun parse(json: String): List<CronJobDto> = gson.fromJson(json, listType)

    @Test
    fun `parses ISO timestamps with both Z and offset forms`() {
        assertEquals(parseIso8601Millis("2026-06-21T14:00:00Z"), parseIso8601Millis("2026-06-21T14:00:00+00:00"))
        assertNull(parseIso8601Millis(null))
        assertNull(parseIso8601Millis(""))
        assertNull(parseIso8601Millis("not-a-date"))
    }

    @Test
    fun `keeps only active local future jobs and maps fields`() {
        val json = """
        [
          {"id":"a1","name":"Cook lunch","prompt":"remind to cook","enabled":true,"state":"scheduled",
           "deliver":"local","next_run_at":"2026-06-21T14:00:00+00:00",
           "schedule":{"kind":"once","display":"once at 2026-06-21 14:00"},"repeat":{"times":1}},
          {"id":"b2","name":"Take meds","enabled":true,"state":"scheduled","deliver":"local",
           "next_run_at":"2026-06-21T13:00:00+00:00",
           "schedule":{"kind":"interval","display":"every 1h"},"repeat":{"times":null}},
          {"id":"tg","name":"Email digest","enabled":true,"state":"scheduled","deliver":"telegram",
           "next_run_at":"2026-06-21T15:00:00+00:00","schedule":{"kind":"cron","display":"0 9 * * *"}},
          {"id":"done","name":"Old","enabled":false,"state":"completed","deliver":"local",
           "next_run_at":null,"schedule":{"kind":"once","display":"once"}},
          {"id":"past","name":"Already due","enabled":true,"state":"scheduled","deliver":"local",
           "next_run_at":"2026-06-21T11:00:00+00:00","schedule":{"kind":"once","display":"once"}},
          {"id":"paused","name":"Paused","enabled":false,"state":"paused","deliver":"local",
           "next_run_at":"2026-06-21T16:00:00+00:00","schedule":{"kind":"once","display":"once"}}
        ]
        """.trimIndent()

        val tasks = parse(json).toScheduledTasks(now)

        // Only a1 and b2 survive; sorted by fire time (b2 13:00 before a1 14:00).
        assertEquals(listOf("b2", "a1"), tasks.map { it.id })

        val cook = tasks.first { it.id == "a1" }
        assertEquals("Cook lunch", cook.name)
        assertEquals("once", cook.kind)
        assertEquals("once at 2026-06-21 14:00", cook.scheduleDisplay)
        assertEquals(false, cook.recurring)
        assertEquals(parseIso8601Millis("2026-06-21T14:00:00Z"), cook.nextRunAtMs)

        val meds = tasks.first { it.id == "b2" }
        assertTrue("interval job is recurring", meds.recurring)
        assertEquals("every 1h", meds.scheduleDisplay)
    }

    @Test
    fun `falls back to prompt then default for missing name`() {
        val json = """
        [
          {"id":"p1","prompt":"water the plants","enabled":true,"state":"scheduled","deliver":"local",
           "next_run_at":"2026-06-21T20:00:00Z","schedule":{"kind":"once","display":"once"}},
          {"id":"p2","enabled":true,"state":"scheduled","deliver":"local",
           "next_run_at":"2026-06-21T21:00:00Z","schedule":{"kind":"once"}}
        ]
        """.trimIndent()

        val tasks = parse(json).toScheduledTasks(now).associateBy { it.id }
        assertEquals("water the plants", tasks.getValue("p1").name)
        assertEquals("Reminder", tasks.getValue("p2").name)
        // schedule.display absent -> falls back to kind.
        assertEquals("once", tasks.getValue("p2").scheduleDisplay)
    }
}

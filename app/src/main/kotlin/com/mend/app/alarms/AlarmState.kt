package com.mend.app.alarms

import android.content.Context
import com.mend.engine.alarms.AlarmCriticality
import com.mend.engine.alarms.AlarmKind
import com.mend.engine.alarms.ScheduledAlarm
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is currently armed.
 *
 * Persisted because it has to outlive the process: the diff that avoids
 * needlessly re-arming everything is only meaningful if we still know what was
 * armed before the app was killed. Held as JSON in preferences rather than in
 * the database, since this is bookkeeping about the platform rather than
 * anything the user owns or the engine reasons about.
 */
class AlarmState(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var armed: List<ScheduledAlarm>
        get() = read()
        set(value) = write(value)

    private fun read(): List<ScheduledAlarm> {
        val raw = prefs.getString(KEY_ARMED, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.toAlarm()
            }
        }.getOrDefault(emptyList())
    }

    private fun write(alarms: List<ScheduledAlarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ARMED, array.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY_ARMED).apply()

    private fun ScheduledAlarm.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("at", at.toString())
        put("kind", kind.name)
        put("criticality", criticality.name)
        put("title", title)
        put("body", body)
        put("taskId", taskId)
    }

    private fun JSONObject.toAlarm(): ScheduledAlarm? = runCatching {
        ScheduledAlarm(
            id = getString("id"),
            at = LocalDateTime.parse(getString("at")),
            kind = AlarmKind.valueOf(getString("kind")),
            criticality = AlarmCriticality.valueOf(getString("criticality")),
            title = optString("title"),
            body = optString("body"),
            taskId = optString("taskId").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrNull()

    private companion object {
        const val FILE = "mend_alarm_state"
        const val KEY_ARMED = "armed"
    }
}

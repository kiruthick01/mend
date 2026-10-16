package com.mend.app.progress

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The outside apps Mend can read progress from. */
enum class ProgressKind(val label: String, val packageName: String) {
    DUOLINGO("Duolingo", "com.duolingo"),
    ANKI("Anki", "com.ichi2.anki"),
}

/**
 * One goal tied to one outside app.
 *
 * [target] is the Duolingo username or the AnkiDroid deck name. Blank means no
 * target: the link falls back to counting time spent in the app, which needs
 * nothing but the usage-access permission.
 */
data class ProgressLink(
    val goalId: String,
    val kind: ProgressKind,
    val target: String = "",
    /** The last day this link completed a sub-task, so a day is only counted once. */
    val lastAppliedOn: LocalDate? = null,
) {
    val key: String get() = "$goalId|${kind.name}"
}

/**
 * Where the links live. Plain SharedPreferences, like [com.mend.app.ai.AiSettings]:
 * a handful of rows with no derived state does not earn a Room migration.
 */
class ProgressLinkStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)

    /** Bumps whenever links or records change, so schedule inputs can recompute. */
    val revision: StateFlow<Int> = _revision

    /** Signals that something time-dependent changed and the day should be re-ranked. */
    fun touch() {
        _revision.value++
    }

    /** Recorded days, newest data per (day, link). */
    fun records(key: String? = null): List<DayRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val all = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { DayRecord.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return if (key == null) all else all.filter { it.key == key }
    }

    fun upsert(record: DayRecord) {
        val all = records()
        val existing = all.firstOrNull { it.date == record.date && it.key == record.key }
        val merged = existing?.merge(record) ?: record
        if (merged == existing) return
        val cutoff = record.date.minusDays(RETAIN_DAYS)
        val next = all.filterNot { it.date == record.date && it.key == record.key }
            .plus(merged)
            .filter { it.date > cutoff }
        prefs.edit().putString(KEY_RECORDS, JSONArray().apply { next.forEach { put(it.toJson()) } }.toString()).apply()
        _revision.value++
    }

    fun all(): List<ProgressLink> {
        val raw = prefs.getString(KEY_LINKS, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    fun save(link: ProgressLink) {
        write(all().filterNot { it.key == link.key } + link)
    }

    fun remove(link: ProgressLink) {
        write(all().filterNot { it.key == link.key })
    }

    /** Highest due-card count seen for [deck] today; the baseline for "finished". */
    fun ankiBaseline(deck: String, today: LocalDate): Int =
        prefs.getInt("anki_base_${today}_$deck", 0)

    fun setAnkiBaseline(deck: String, today: LocalDate, value: Int) {
        prefs.edit().putInt("anki_base_${today}_$deck", value).apply()
    }

    private fun write(links: List<ProgressLink>) {
        prefs.edit().putString(KEY_LINKS, encode(links)).apply()
        _revision.value++
    }

    companion object {
        private const val FILE = "mend_progress_links"
        private const val KEY_LINKS = "links"
        private const val KEY_RECORDS = "records"
        private const val RETAIN_DAYS = 60L

        internal fun encode(links: List<ProgressLink>): String = JSONArray().apply {
            links.forEach {
                put(
                    JSONObject()
                        .put("goalId", it.goalId)
                        .put("kind", it.kind.name)
                        .put("target", it.target)
                        .put("lastAppliedOn", it.lastAppliedOn?.toString()),
                )
            }
        }.toString()

        internal fun decode(raw: String): List<ProgressLink> {
            val array = JSONArray(raw)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val kind = ProgressKind.entries.firstOrNull { it.name == o.optString("kind") }
                    ?: return@mapNotNull null
                ProgressLink(
                    goalId = o.getString("goalId"),
                    kind = kind,
                    target = o.optString("target"),
                    lastAppliedOn = o.optString("lastAppliedOn")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?.let(LocalDate::parse),
                )
            }
        }
    }
}

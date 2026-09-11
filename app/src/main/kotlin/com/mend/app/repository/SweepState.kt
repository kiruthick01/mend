package com.mend.app.repository

import android.content.Context
import java.time.LocalDate

/**
 * How far the catch-up sweep has got.
 *
 * Deliberately not a Room table: this is bookkeeping about the app, not
 * something the user owns or the engine reasons about, and it should not show
 * up in a schema export next to the adherence stream.
 */
class SweepState(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastSweptDate: LocalDate?
        get() = prefs.getString(KEY_LAST_SWEPT, null)?.let(LocalDate::parse)
        set(value) {
            prefs.edit().putString(KEY_LAST_SWEPT, value?.toString()).apply()
        }

    private companion object {
        const val FILE = "mend_sweep_state"
        const val KEY_LAST_SWEPT = "last_swept_date"
    }
}

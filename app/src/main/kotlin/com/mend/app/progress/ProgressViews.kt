package com.mend.app.progress

import com.mend.app.MendApplication
import java.time.LocalDate

/** All links' status lines joined for the widget, or null when nothing is linked. */
fun progressLineFor(app: MendApplication, today: LocalDate): String? {
    val links = app.progressLinks.all()
    if (links.isEmpty()) return null
    return links.joinToString("   ") {
        ProgressInsights.summaryLine(it.kind, app.progressLinks.records(it.key), today)
    }
}

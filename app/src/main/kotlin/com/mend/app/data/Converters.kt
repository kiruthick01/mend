package com.mend.app.data

import androidx.room.TypeConverter
import com.mend.engine.model.AdherenceOutcome
import com.mend.engine.model.ConsequenceType
import com.mend.engine.model.Flexibility
import com.mend.engine.model.ProgressStatus
import com.mend.engine.model.TaskType
import com.mend.engine.model.UnitDuration
import com.mend.engine.model.WindowAnchor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Room converters for the engine's value types.
 *
 * Times are stored as minutes/epoch-days rather than formatted strings so
 * ordering and range queries work directly in SQL. Enums are stored by name so
 * a schema dump stays readable and reordering an enum cannot silently
 * repoint existing rows.
 */
class Converters {

    @TypeConverter
    fun localTimeToMinutes(value: LocalTime?): Int? = value?.toSecondOfDay()?.div(60)

    @TypeConverter
    fun minutesToLocalTime(value: Int?): LocalTime? =
        value?.let { LocalTime.ofSecondOfDay(it.toLong() * 60) }

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)

    @TypeConverter
    fun daysToString(value: Set<DayOfWeek>?): String? =
        value?.sorted()?.joinToString(",") { it.name }

    @TypeConverter
    fun stringToDays(value: String?): Set<DayOfWeek>? = value?.let { raw ->
        if (raw.isBlank()) emptySet()
        else raw.split(",").map { DayOfWeek.valueOf(it.trim()) }.toSet()
    }

    @TypeConverter
    fun taskTypeToString(value: TaskType?): String? = value?.name

    @TypeConverter
    fun stringToTaskType(value: String?): TaskType? = value?.let(TaskType::valueOf)

    @TypeConverter
    fun consequenceToString(value: ConsequenceType?): String? = value?.name

    @TypeConverter
    fun stringToConsequence(value: String?): ConsequenceType? =
        value?.let(ConsequenceType::valueOf)

    @TypeConverter
    fun flexibilityToString(value: Flexibility?): String? = value?.name

    @TypeConverter
    fun stringToFlexibility(value: String?): Flexibility? = value?.let(Flexibility::valueOf)

    @TypeConverter
    fun unitDurationToString(value: UnitDuration?): String? = value?.name

    @TypeConverter
    fun stringToUnitDuration(value: String?): UnitDuration? = value?.let(UnitDuration::valueOf)

    @TypeConverter
    fun anchorToString(value: WindowAnchor?): String? = value?.name

    @TypeConverter
    fun stringToAnchor(value: String?): WindowAnchor? = value?.let(WindowAnchor::valueOf)

    @TypeConverter
    fun progressToString(value: ProgressStatus?): String? = value?.name

    @TypeConverter
    fun stringToProgress(value: String?): ProgressStatus? = value?.let(ProgressStatus::valueOf)

    @TypeConverter
    fun outcomeToString(value: AdherenceOutcome?): String? = value?.name

    @TypeConverter
    fun stringToOutcome(value: String?): AdherenceOutcome? = value?.let(AdherenceOutcome::valueOf)
}

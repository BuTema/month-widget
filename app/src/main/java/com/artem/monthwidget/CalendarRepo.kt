package com.artem.monthwidget

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class CalInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val visible: Boolean,
)

data class Ev(
    val id: Long,
    val title: String,
    val color: Int,
    val allDay: Boolean,
    val begin: Long,
)

data class TitleInfo(val title: String, val color: Int, val count: Int)

/** Какие дни показывает виджет. */
data class Plan(
    val start: LocalDate,
    val weeks: Int,
    /** Не null только в режиме «месяц» — дни других месяцев приглушаются */
    val focus: YearMonth?,
) {
    val end: LocalDate get() = start.plusDays(weeks * 7L - 1)
}

fun planFor(s: Settings, today: LocalDate): Plan {
    val first = if (s.mondayFirst) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(first))

    return if (s.weeksMode == 0) {
        val ym = YearMonth.from(today).plusMonths(s.pageOffset.toLong())
        val start = weekStart(ym.atDay(1))
        val daysBetween = ChronoUnit.DAYS.between(start, ym.atEndOfMonth())
        Plan(start, (daysBetween / 7).toInt() + 1, ym)
    } else {
        Plan(weekStart(today).plusWeeks(s.pageOffset.toLong()), s.weeksMode.coerceIn(1, 6), null)
    }
}

object CalendarRepo {
    private const val GOOGLE = "com.google"

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Только календари аккаунтов Google. */
    fun googleCalendars(ctx: Context): List<CalInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE,
        )
        val out = ArrayList<CalInfo>()
        try {
            ctx.contentResolver.query(
                Calendars.CONTENT_URI,
                projection,
                "${Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(GOOGLE),
                "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        account = c.getString(2) ?: "",
                        color = c.getInt(3) or 0xFF000000.toInt(),
                        visible = c.getInt(4) != 0,
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    /**
     * При первом запуске выключаем календари, которые скрыты в самом Google Календаре
     * (например, «Дни рождения»), остальные включены.
     */
    fun ensureCalendarDefaults(ctx: Context) {
        val p = Prefs.prefs(ctx)
        if (p.getBoolean(Prefs.KEY_CALENDARS_INITIALIZED, false) || !hasPermission(ctx)) return
        val all = googleCalendars(ctx)
        if (all.isEmpty()) return // ещё не синхронизировался — попробуем позже
        val hidden = all.filter { !it.visible }.map { it.id.toString() }.toHashSet()
        p.edit()
            .putStringSet(Prefs.KEY_DISABLED_CALENDARS, hidden)
            .putBoolean(Prefs.KEY_CALENDARS_INITIALIZED, true)
            .apply()
    }

    private class Raw(
        val eventId: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val calendarId: Long,
        val color: Int,
        val selfStatus: Int,
    )

    private fun forEachInstance(ctx: Context, from: Long, to: Long, action: (Raw) -> Unit) {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,        // 0
            Instances.TITLE,           // 1
            Instances.BEGIN,           // 2
            Instances.END,             // 3
            Instances.ALL_DAY,         // 4
            Instances.CALENDAR_ID,     // 5
            Instances.EVENT_COLOR,     // 6
            Instances.CALENDAR_COLOR,  // 7
            Instances.SELF_ATTENDEE_STATUS, // 8
            Instances.STATUS,          // 9
        )
        try {
            ctx.contentResolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(9) == CalendarContract.Events.STATUS_CANCELED) continue
                    val eventColor = c.getInt(6)
                    val color = (if (eventColor != 0) eventColor else c.getInt(7)) or 0xFF000000.toInt()
                    action(
                        Raw(
                            eventId = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(без названия)",
                            begin = c.getLong(2),
                            end = c.getLong(3),
                            allDay = c.getInt(4) != 0,
                            calendarId = c.getLong(5),
                            color = color,
                            selfStatus = c.getInt(8),
                        ),
                    )
                }
            }
        } catch (_: SecurityException) {
        }
    }

    /** События для клеток виджета: день -> отсортированный список. */
    fun loadEvents(ctx: Context, s: Settings, plan: Plan): Map<LocalDate, List<Ev>> {
        if (!hasPermission(ctx)) return emptyMap()
        val zone = ZoneId.systemDefault()
        val googleIds = googleCalendars(ctx).map { it.id }.toHashSet()
        val hidden = s.hiddenTitles.map(::normTitle).toHashSet()

        // С запасом в сутки по краям: «весь день» события хранятся в UTC.
        val from = plan.start.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = plan.end.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

        val byDay = HashMap<LocalDate, MutableList<Ev>>()
        forEachInstance(ctx, from, to) { r ->
            if (r.calendarId !in googleIds || r.calendarId in s.disabledCalendars) return@forEachInstance
            if (normTitle(r.title) in hidden) return@forEachInstance
            if (s.hideDeclined && r.selfStatus == ATTENDEE_DECLINED) return@forEachInstance
            if (s.hideUnanswered && r.selfStatus == ATTENDEE_INVITED) return@forEachInstance

            val firstDay: LocalDate
            val lastDay: LocalDate
            if (r.allDay) {
                firstDay = java.time.Instant.ofEpochMilli(r.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val endExcl = java.time.Instant.ofEpochMilli(r.end).atZone(ZoneOffset.UTC).toLocalDate()
                lastDay = endExcl.minusDays(1).let { if (it.isBefore(firstDay)) firstDay else it }
            } else {
                firstDay = java.time.Instant.ofEpochMilli(r.begin).atZone(zone).toLocalDate()
                val endZ = java.time.Instant.ofEpochMilli(r.end).atZone(zone)
                val raw = if (endZ.toLocalTime() == LocalTime.MIDNIGHT && r.end > r.begin) {
                    endZ.toLocalDate().minusDays(1)
                } else {
                    endZ.toLocalDate()
                }
                lastDay = if (raw.isBefore(firstDay)) firstDay else raw
            }

            val ev = Ev(r.eventId, r.title, r.color, r.allDay, r.begin)
            var d = if (firstDay.isBefore(plan.start)) plan.start else firstDay
            val stop = if (lastDay.isAfter(plan.end)) plan.end else lastDay
            while (!d.isAfter(stop)) {
                byDay.getOrPut(d) { ArrayList() }.add(ev)
                d = d.plusDays(1)
            }
        }
        val order = compareBy<Ev>({ !it.allDay }, { it.begin }, { it.title })
        return byDay.mapValues { (_, list) -> list.sortedWith(order) }
    }

    /** Названия ближайших событий — для списка «скрыть событие» в настройках. */
    fun upcomingTitles(ctx: Context, s: Settings, days: Int): List<TitleInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val zone = ZoneId.systemDefault()
        val googleIds = googleCalendars(ctx).map { it.id }.toHashSet()
        val today = LocalDate.now()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

        val found = LinkedHashMap<String, TitleInfo>()
        forEachInstance(ctx, from, to) { r ->
            if (r.calendarId !in googleIds || r.calendarId in s.disabledCalendars) return@forEachInstance
            val key = normTitle(r.title)
            val old = found[key]
            found[key] = if (old == null) TitleInfo(r.title, r.color, 1) else old.copy(count = old.count + 1)
        }
        // Скрытые названия без ближайших событий тоже показываем — чтобы их можно было вернуть.
        for (h in s.hiddenTitles) {
            val key = normTitle(h)
            if (key !in found) found[key] = TitleInfo(h, 0xFF90A4AE.toInt(), 0)
        }
        return found.values.sortedBy { it.title.lowercase() }
    }

    private const val ATTENDEE_DECLINED = 2 // CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
    private const val ATTENDEE_INVITED = 3 // CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
}

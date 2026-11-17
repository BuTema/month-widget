package com.artem.monthwidget

import android.content.Context

/**
 * Настройки виджета. Один набор на все экземпляры виджета.
 */
data class Settings(
    /** 0 = месяц целиком (4–6 недель), 1..5 = столько недель, начиная с текущей */
    val weeksMode: Int = 0,
    /** Непрозрачность фона, 0..100 */
    val bgAlpha: Int = 60,
    /** 0 = как в системе, 1 = тёмная, 2 = светлая */
    val theme: Int = 0,
    /** Сколько событий показывать в клетке дня (минимум 2) */
    val eventsPerDay: Int = 2,
    /** Размер шрифта, sp */
    val fontSp: Int = 10,
    val mondayFirst: Boolean = true,
    val hideDeclined: Boolean = true,
    /** Скрывать приглашения, на которые ещё не ответили */
    val hideUnanswered: Boolean = false,
    val disabledCalendars: Set<Long> = emptySet(),
    /** Названия событий, которые скрываются (сравнение без учёта регистра) */
    val hiddenTitles: Set<String> = emptySet(),
    /** Смещение страницы: месяцы (режим «месяц») или недели (режим N недель) */
    val pageOffset: Int = 0,
)

object Prefs {
    private const val FILE = "settings"
    const val KEY_CALENDARS_INITIALIZED = "calendars_initialized"
    const val KEY_DISABLED_CALENDARS = "disabled_calendars"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): Settings {
        val p = prefs(ctx)
        val d = Settings()
        return Settings(
            weeksMode = p.getInt("weeks_mode", d.weeksMode),
            bgAlpha = p.getInt("bg_alpha", d.bgAlpha),
            theme = p.getInt("theme", d.theme),
            eventsPerDay = p.getInt("events_per_day", d.eventsPerDay).coerceAtLeast(2),
            fontSp = p.getInt("font_sp", d.fontSp),
            mondayFirst = p.getBoolean("monday_first", d.mondayFirst),
            hideDeclined = p.getBoolean("hide_declined", d.hideDeclined),
            hideUnanswered = p.getBoolean("hide_unanswered", d.hideUnanswered),
            disabledCalendars = (p.getStringSet(KEY_DISABLED_CALENDARS, emptySet()) ?: emptySet())
                .mapNotNull { it.toLongOrNull() }
                .toSet(),
            hiddenTitles = (p.getStringSet("hidden_titles", emptySet()) ?: emptySet()).toSet(),
            pageOffset = p.getInt("page_offset", d.pageOffset),
        )
    }

    fun save(ctx: Context, s: Settings) {
        prefs(ctx).edit()
            .putInt("weeks_mode", s.weeksMode)
            .putInt("bg_alpha", s.bgAlpha)
            .putInt("theme", s.theme)
            .putInt("events_per_day", s.eventsPerDay)
            .putInt("font_sp", s.fontSp)
            .putBoolean("monday_first", s.mondayFirst)
            .putBoolean("hide_declined", s.hideDeclined)
            .putBoolean("hide_unanswered", s.hideUnanswered)
            .putStringSet(KEY_DISABLED_CALENDARS, HashSet(s.disabledCalendars.map { it.toString() }))
            .putStringSet("hidden_titles", HashSet(s.hiddenTitles))
            .putInt("page_offset", s.pageOffset)
            .apply()
    }

    fun setPageOffset(ctx: Context, offset: Int) {
        prefs(ctx).edit().putInt("page_offset", offset).apply()
    }

    fun pageOffset(ctx: Context): Int = prefs(ctx).getInt("page_offset", 0)
}

fun normTitle(t: String): String = t.trim().lowercase()

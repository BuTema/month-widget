package com.artem.monthwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.LocalContext
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

class MonthWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) {
            CalendarRepo.ensureCalendarDefaults(context)
            val settings = Prefs.load(context)
            val today = LocalDate.now()
            val plan = planFor(settings, today)
            MonthData(
                settings = settings,
                plan = plan,
                today = today,
                hasPermission = CalendarRepo.hasPermission(context),
                events = CalendarRepo.loadEvents(context, settings, plan),
            )
        }
        provideContent { MonthContent(data) }
    }
}

data class MonthData(
    val settings: Settings,
    val plan: Plan,
    val today: LocalDate,
    val hasPermission: Boolean,
    val events: Map<LocalDate, List<Ev>>,
)

// ---------------------------------------------------------------- palette

private class Palette(
    val bg: ColorProvider,
    val text: ColorProvider,
    val dim: ColorProvider,
    val accent: ColorProvider,
    val todayTint: ColorProvider,
)

private fun palette(s: Settings): Palette {
    val a = s.bgAlpha.coerceIn(0, 100) / 100f
    fun cp(light: Color, dark: Color): ColorProvider = when (s.theme) {
        1 -> ColorProvider(day = dark, night = dark)
        2 -> ColorProvider(day = light, night = light)
        else -> ColorProvider(day = light, night = dark)
    }
    return Palette(
        bg = cp(Color.White.copy(alpha = a), Color(0xFF16161A).copy(alpha = a)),
        text = cp(Color(0xFF1B1B1F), Color(0xFFF2F2F5)),
        dim = cp(Color(0xFF8A8A93), Color(0xFF7C7C86)),
        accent = cp(Color(0xFF1565C0), Color(0xFF64B5F6)),
        todayTint = cp(Color(0x261565C0), Color(0x3364B5F6)),
    )
}

// ---------------------------------------------------------------- content

@androidx.compose.runtime.Composable
private fun MonthContent(d: MonthData) {
    val s = d.settings
    val p = palette(s)
    val openSettings = actionStartActivity(Intent(LocalContext.current, SettingsActivity::class.java))

    if (!d.hasPermission) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(p.bg)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(openSettings),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Нужен доступ к календарю.\nНажмите, чтобы открыть настройки.",
                style = TextStyle(color = p.text, fontSize = 12.sp, textAlign = TextAlign.Center),
            )
        }
        return
    }

    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val plan = d.plan

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(p.bg)
            .cornerRadius(16.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Header(plan, p, locale, s, openSettings)
        WeekdayRow(plan, p, locale, s)

        for (w in 0 until plan.weeks) {
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (i in 0 until 7) {
                    val day = plan.start.plusDays((w * 7 + i).toLong())
                    DayCell(
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        day = day,
                        inFocus = plan.focus == null || YearMonth.from(day) == plan.focus,
                        isToday = day == d.today,
                        events = d.events[day].orEmpty(),
                        s = s,
                        p = p,
                        zone = zone,
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Header(plan: Plan, p: Palette, locale: Locale, s: Settings, openSettings: Action) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = headerLabel(plan, locale),
            modifier = GlanceModifier
                .defaultWeight()
                .padding(vertical = 3.dp)
                .clickable(actionRunCallback<ResetAction>()),
            maxLines = 1,
            style = TextStyle(
                color = p.text,
                fontSize = (s.fontSp + 3).sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        IconButton(R.drawable.ic_chevron_left, "Назад", p, actionRunCallback<ShiftAction>(
            actionParametersOf(ShiftAction.DIR to -1),
        ))
        IconButton(R.drawable.ic_chevron_right, "Вперёд", p, actionRunCallback<ShiftAction>(
            actionParametersOf(ShiftAction.DIR to 1),
        ))
        IconButton(R.drawable.ic_settings, "Настройки", p, openSettings)
    }
}

@androidx.compose.runtime.Composable
private fun IconButton(
    res: Int,
    description: String,
    p: Palette,
    action: Action,
) {
    Box(
        modifier = GlanceModifier.size(26.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(res),
            contentDescription = description,
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(p.text),
        )
    }
}

@androidx.compose.runtime.Composable
private fun WeekdayRow(plan: Plan, p: Palette, locale: Locale, s: Settings) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        for (i in 0 until 7) {
            val dow: DayOfWeek = plan.start.plusDays(i.toLong()).dayOfWeek
            val label = dow.getDisplayName(JTextStyle.SHORT, locale)
                .replaceFirstChar { it.titlecase(locale) }
            Text(
                text = label,
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
                style = TextStyle(
                    color = p.dim,
                    fontSize = (s.fontSp - 1).coerceAtLeast(7).sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun DayCell(
    modifier: GlanceModifier,
    day: LocalDate,
    inFocus: Boolean,
    isToday: Boolean,
    events: List<Ev>,
    s: Settings,
    p: Palette,
    zone: ZoneId,
) {
    val millis = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val open = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
    val shown = events.take(s.eventsPerDay)
    val more = events.size - shown.size

    // Фон задаём у каждой клетки (у остальных дней прозрачный): RemoteViews при обновлении накладывается
    // на старые вью и не сбрасывает то, что не задано заново, — иначе подсветка вчерашнего дня остаётся.
    val cell = modifier
        .padding(1.dp)
        .background(if (isToday) p.todayTint else ColorProvider(Color.Transparent))
        .cornerRadius(6.dp)

    Column(modifier = cell.clickable(actionStartActivity(open))) {
        Row(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text(
                text = day.dayOfMonth.toString(),
                maxLines = 1,
                style = TextStyle(
                    color = when {
                        isToday -> p.accent
                        inFocus -> p.text
                        else -> p.dim
                    },
                    fontSize = s.fontSp.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (more > 0) {
                Text(
                    text = "+$more",
                    maxLines = 1,
                    style = TextStyle(color = p.dim, fontSize = (s.fontSp - 2).coerceAtLeast(7).sp),
                )
            }
        }
        for (ev in shown) {
            EventLine(ev, s, p, inFocus)
        }
    }
}

@androidx.compose.runtime.Composable
private fun EventLine(ev: Ev, s: Settings, p: Palette, inFocus: Boolean) {
    val size = (s.fontSp - 1).coerceAtLeast(7).sp
    val color = Color(ev.color)
    if (ev.allDay) {
        val chipAlpha = if (inFocus) 0.92f else 0.5f
        val onChip = if (color.luminance() > 0.6f) Color(0xFF111111) else Color.White
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 1.dp)
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ColorProvider(color.copy(alpha = chipAlpha)))
                    .cornerRadius(3.dp)
                    .padding(horizontal = 2.dp),
            ) {
                Text(
                    text = ev.title,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(onChip), fontSize = size),
                )
            }
        }
    } else {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(4.dp)
                    .background(ColorProvider(color))
                    .cornerRadius(2.dp),
            ) {}
            Spacer(modifier = GlanceModifier.width(2.dp))
            Text(
                text = ev.title,
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
                style = TextStyle(color = if (inFocus) p.text else p.dim, fontSize = size),
            )
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun headerLabel(plan: Plan, locale: Locale): String {
    fun String.cap() = replaceFirstChar { it.titlecase(locale) }
    val full = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val short = DateTimeFormatter.ofPattern("LLL", locale)
    val focus = plan.focus
    if (focus != null) return full.format(focus.atDay(1)).cap()
    val a = plan.start
    val b = plan.end
    return if (YearMonth.from(a) == YearMonth.from(b)) {
        full.format(a).cap()
    } else if (a.year == b.year) {
        "${short.format(a).cap()} – ${full.format(b).cap()}"
    } else {
        "${full.format(a).cap()} – ${full.format(b).cap()}"
    }
}

// ---------------------------------------------------------------- actions

class ShiftAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val dir = parameters[DIR] ?: 0
        Prefs.setPageOffset(context, Prefs.pageOffset(context) + dir)
        MonthWidget().updateAll(context)
    }

    companion object {
        val DIR = ActionParameters.Key<Int>("dir")
    }
}

class ResetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Prefs.setPageOffset(context, 0)
        MonthWidget().updateAll(context)
    }
}

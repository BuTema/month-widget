package com.artem.monthwidget

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme { SettingsScreen() }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(Prefs.load(ctx)) }
    var hasPerm by remember { mutableStateOf(CalendarRepo.hasPermission(ctx)) }
    var calendars by remember { mutableStateOf(emptyList<CalInfo>()) }
    var titles by remember { mutableStateOf(emptyList<TitleInfo>()) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    var alphaDrag by remember { mutableFloatStateOf(settings.bgAlpha.toFloat()) }

    fun commit(new: Settings) {
        settings = new
        Prefs.save(ctx, new)
        scope.launch { MonthWidget().updateAll(ctx) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
    }

    // Новая сборка на GitHub? Проверяется не чаще раза в час.
    LaunchedEffect(Unit) { update = AppUpdater.check(ctx) }

    // Первый запуск после выдачи доступа: выставляем начальные включённые календари.
    LaunchedEffect(hasPerm) {
        if (hasPerm) {
            withContext(Dispatchers.IO) { CalendarRepo.ensureCalendarDefaults(ctx) }
            settings = Prefs.load(ctx)
            MonthWidget().updateAll(ctx)
        }
    }

    // Список календарей и названий событий; перечитываем при включении/выключении календаря.
    LaunchedEffect(hasPerm, settings.disabledCalendars) {
        if (hasPerm) {
            val current = Prefs.load(ctx)
            val (c, t) = withContext(Dispatchers.IO) {
                CalendarRepo.googleCalendars(ctx) to CalendarRepo.upcomingTitles(ctx, current, 60)
            }
            calendars = c
            titles = t
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Календарь") }) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            update?.let { u ->
                item {
                    UpdateCard(
                        onLater = {
                            AppUpdater.dismiss(ctx, u)
                            update = null
                        },
                        onInstall = { AppUpdater.install(ctx, u) },
                    )
                }
            }

            if (!hasPerm) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Нужен доступ к календарю", fontWeight = FontWeight.Bold)
                            Text("Виджет читает события из Google Календаря, который уже синхронизирован на телефоне. Данные никуда не отправляются.")
                            Button(onClick = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                                Text("Разрешить")
                            }
                        }
                    }
                }
            }

            item { Section("Вид") }
            item {
                LabeledGroup("Сколько недель показывать") {
                ChoiceRow(
                    options = listOf(0 to "Месяц", 1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5"),
                    selected = settings.weeksMode,
                ) { commit(settings.copy(weeksMode = it, pageOffset = 0)) }
                }
            }
            item {
                LabeledGroup("Непрозрачность фона: ${alphaDrag.roundToInt()}%") {
                Slider(
                    value = alphaDrag,
                    onValueChange = { alphaDrag = it },
                    onValueChangeFinished = { commit(settings.copy(bgAlpha = alphaDrag.roundToInt())) },
                    valueRange = 0f..100f,
                )
                }
            }
            item {
                LabeledGroup("Тема виджета") {
                ChoiceRow(
                    options = listOf(0 to "Как в системе", 1 to "Тёмная", 2 to "Светлая"),
                    selected = settings.theme,
                ) { commit(settings.copy(theme = it)) }
                }
            }
            item {
                LabeledGroup("Событий в клетке дня") {
                ChoiceRow(
                    options = listOf(2 to "2", 3 to "3", 4 to "4"),
                    selected = settings.eventsPerDay,
                ) { commit(settings.copy(eventsPerDay = it)) }
                }
            }
            item {
                LabeledGroup("Размер текста") {
                ChoiceRow(
                    options = listOf(8 to "8", 9 to "9", 10 to "10", 11 to "11", 12 to "12"),
                    selected = settings.fontSp,
                ) { commit(settings.copy(fontSp = it)) }
                }
            }
            item {
                LabeledGroup("Неделя начинается с") {
                ChoiceRow(
                    options = listOf(1 to "Понедельника", 0 to "Воскресенья"),
                    selected = if (settings.mondayFirst) 1 else 0,
                ) { commit(settings.copy(mondayFirst = it == 1, pageOffset = 0)) }
                }
            }

            item { Section("Фильтры") }
            item {
                SwitchRow(
                    title = "Скрывать отклонённые",
                    subtitle = "События, на которые вы ответили «Нет»",
                    checked = settings.hideDeclined,
                ) { commit(settings.copy(hideDeclined = it)) }
            }
            item {
                SwitchRow(
                    title = "Скрывать приглашения без ответа",
                    subtitle = "Пока вы не нажали «Да» или «Возможно»",
                    checked = settings.hideUnanswered,
                ) { commit(settings.copy(hideUnanswered = it)) }
            }

            if (hasPerm) {
                item { Section("Календари Google") }
                if (calendars.isEmpty()) {
                    item { Text("Календари Google не найдены. Проверьте, что аккаунт добавлен на телефоне и синхронизация включена.") }
                }
                items(calendars, key = { "cal-${it.id}" }) { cal ->
                    SwitchRow(
                        title = cal.name,
                        subtitle = cal.account,
                        checked = cal.id !in settings.disabledCalendars,
                        dot = Color(cal.color),
                    ) { on ->
                        val disabled = if (on) settings.disabledCalendars - cal.id else settings.disabledCalendars + cal.id
                        commit(settings.copy(disabledCalendars = disabled))
                    }
                }

                item { Section("Скрыть события") }
                item {
                    Text(
                        "Выключите переключатель, чтобы убрать с виджета все события с таким названием " +
                            "(повторяющиеся серии скрываются целиком). Список — события на 60 дней вперёд.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (titles.isEmpty()) {
                    item { Text("Ближайших событий нет.") }
                }
                items(titles, key = { "t-${normTitle(it.title)}" }) { t ->
                    val key = normTitle(t.title)
                    val hidden = settings.hiddenTitles.any { normTitle(it) == key }
                    SwitchRow(
                        title = t.title,
                        subtitle = if (t.count > 0) "Событий: ${t.count}" else "Сейчас нет ближайших",
                        checked = !hidden,
                        dot = Color(t.color),
                    ) { show ->
                        val new = if (show) {
                            settings.hiddenTitles.filterNot { normTitle(it) == key }.toSet()
                        } else {
                            settings.hiddenTitles + t.title
                        }
                        commit(settings.copy(hiddenTitles = new))
                    }
                }
            }

            item { Section("О приложении") }
            item { AboutBlock() }

            item { Box(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LabeledGroup(text: String, content: @Composable () -> Unit) {
    Column {
        Text(text, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    dot: Color? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(12.dp).background(dot, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

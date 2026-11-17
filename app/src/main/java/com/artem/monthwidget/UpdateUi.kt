package com.artem.monthwidget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** "A new version is available" card at the top of the settings screen. */
@Composable
fun UpdateCard(onLater: () -> Unit, onInstall: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Доступно обновление", style = MaterialTheme.typography.titleMedium)
            Text(
                "Новая версия приложения готова к установке.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onLater) { Text("Позже") }
                Button(onClick = onInstall) { Text("Обновить") }
            }
        }
    }
}

/** Version line and the "check for updates" button. */
@Composable
fun AboutBlock() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT})")
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !checking,
            onClick = {
                checking = true
                status = null
                scope.launch {
                    val result = AppUpdater.checkNow(ctx)
                    found = result.info
                    status = when {
                        result.failed -> "Не удалось проверить обновления"
                        result.info != null -> "Доступна новая версия"
                        else -> "У вас последняя версия"
                    }
                    checking = false
                }
            },
        ) {
            Text(if (checking) "Проверка…" else "Проверить обновления")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        found?.let { update ->
            Button(modifier = Modifier.fillMaxWidth(), onClick = { AppUpdater.install(ctx, update) }) {
                Text("Обновить")
            }
        }
    }
}

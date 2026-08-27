package com.droidspaces.app.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidspaces.app.R
import com.droidspaces.app.util.AnlandAppManager
import com.droidspaces.app.util.AnlandUtils
import com.droidspaces.app.util.LinuxDesktopApp
import kotlinx.coroutines.launch

/**
 * WSLg-like Linux application picker. Each selected app is rendered through an
 * independent KWin/Anland session and therefore appears as its own Android task.
 */
@Composable
fun AnlandAppsCard(
    containerName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.anland_apps_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = context.getString(R.string.anland_apps_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { showDialog = true },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(context.getString(R.string.anland_apps_open))
            }
        }
    }

    if (showDialog) {
        AnlandAppsDialog(
            containerName = containerName,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun AnlandAppsDialog(
    containerName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<LinuxDesktopApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var customCommand by remember { mutableStateOf("") }
    var launching by remember { mutableStateOf<String?>(null) }
    var sharedMode by remember { mutableStateOf(false) }

    LaunchedEffect(containerName) {
        loading = true
        apps = AnlandAppManager.listApps(containerName)
        loading = false
    }

    fun launch(app: LinuxDesktopApp) {
        if (launching != null) return
        launching = app.name
        scope.launch {
            val result = if (sharedMode) {
                AnlandAppManager.launchSharedApp(containerName, app)
            } else {
                AnlandAppManager.launchApp(containerName, app)
            }
            launching = null
            result.onSuccess { session ->
                AnlandUtils.launchWindow(context, app.name, session.hostSocket)
                onDismiss()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.anland_app_launch_failed,
                        error.message ?: context.getString(R.string.unknown_error),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.exec.contains(q, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = { if (launching == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = context.getString(R.string.anland_apps_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = context.getString(R.string.anland_app_mode_requires_kwin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = launching == null,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Switch(
                            checked = sharedMode,
                            onCheckedChange = { sharedMode = it },
                            enabled = launching == null,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = context.getString(R.string.anland_shared_mode_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = context.getString(R.string.anland_shared_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(context.getString(R.string.anland_apps_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )

                when {
                    loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Spacer(Modifier.size(10.dp))
                            Text(context.getString(R.string.anland_apps_loading))
                        }
                    }

                    filtered.isEmpty() -> {
                        Text(
                            text = context.getString(R.string.anland_apps_empty),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        ) {
                            items(
                                items = filtered,
                                key = { it.desktopFile + "\u0000" + it.name + "\u0000" + it.exec },
                            ) { app ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            app.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            app.exec,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = {
                                        Icon(Icons.Default.Apps, contentDescription = null)
                                    },
                                    trailingContent = {
                                        if (launching == app.name) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        } else {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        }
                                    },
                                    modifier = Modifier.clickable(
                                        enabled = launching == null,
                                        onClick = { launch(app) },
                                    ),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(context.getString(R.string.anland_custom_command)) },
                    placeholder = { Text(context.getString(R.string.anland_custom_command_hint)) },
                )
                Button(
                    onClick = {
                        val command = customCommand.trim()
                        launch(
                            LinuxDesktopApp(
                                name = command.substringBefore(' ').ifBlank { "Linux App" },
                                exec = command,
                            )
                        )
                    },
                    enabled = customCommand.isNotBlank() && launching == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    if (launching != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(context.getString(R.string.anland_launching))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(context.getString(R.string.anland_run))
                    }
                }
            }
        }
    }
}

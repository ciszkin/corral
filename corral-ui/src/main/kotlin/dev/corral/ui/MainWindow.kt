package dev.corral.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.corral.DeviceStatus
import dev.corral.QueueEntry
import kotlinx.coroutines.launch

@Composable
fun MainWindow(store: UiStore, url: String, onScrcpy: suspend (String) -> Unit) {
    val devices by store.devices.collectAsState()
    val queue by store.queue.collectAsState()
    val connection by store.connection.collectAsState()
    val capabilities by store.capabilities.collectAsState()
    val thumbnails by store.thumbnails.collectAsState()
    val denied by store.screenshotDenied.collectAsState()
    val now by store.now.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmFree by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<ActionError?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(8.dp)) {
            ConnectionBar(connection, url)
            if (denied) Text(
                "screenshots denied — is this client named in the daemon's CORRAL_SCREENSHOT_OBSERVERS?",
                color = MaterialTheme.colors.error,
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.weight(2f).fillMaxHeight()) {
                    items(devices, key = { it.serial }) { d ->
                        DeviceCard(
                            device = d,
                            thumb = thumbnails[d.serial]?.toImage(),
                            ttl = ttlLeft(d, now),
                            canFree = capabilities.canForceFree,
                            onLive = {
                                scope.launch {   // scrcpy's early-exit wait must not freeze the UI
                                    runCatching { onScrcpy(d.serial) }.onFailure {
                                        actionError = ActionError("Live view unavailable",
                                            it.message ?: "scrcpy failed to start")
                                    }
                                }
                            },
                            onFree = { confirmFree = d.serial },
                        )
                    }
                }
                QueuePanel(queue, Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (connection is Connection.Disconnected) {
            Text(
                "STALE",
                fontSize = 96.sp,
                modifier = Modifier.align(Alignment.Center).alpha(0.12f),
            )
        }
    }
    confirmFree?.let { serial ->
        val holder = devices.firstOrNull { it.serial == serial }?.lease?.owner ?: "nobody"
        AlertDialog(
            onDismissRequest = { confirmFree = null },
            title = { Text("Force free $serial?") },
            text = { Text("Held by $holder — the holder loses the device immediately.") },
            confirmButton = {
                Button(onClick = {
                    confirmFree = null
                    scope.launch {
                        runCatching { store.forceFree(serial) }.onFailure {
                            actionError = ActionError("Force free failed",
                                it.message ?: "the daemon refused the request")
                        }
                    }
                }) {
                    Text("Force free")
                }
            },
            dismissButton = { TextButton(onClick = { confirmFree = null }) { Text("Cancel") } },
        )
    }
    actionError?.let { error ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            title = { Text(error.title) },
            text = { Text(error.message) },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text("Dismiss") } },
        )
    }
}

private data class ActionError(val title: String, val message: String)

@Composable
private fun ConnectionBar(connection: Connection, url: String) {
    val status = when (connection) {
        is Connection.Connected -> "connected (seq ${connection.seq})"
        Connection.Connecting -> "connecting…"
        is Connection.Disconnected -> "disconnected — view is stale"
    }
    Text("$status — $url", style = MaterialTheme.typography.caption)
}

@Composable
private fun DeviceCard(
    device: DeviceStatus,
    thumb: ImageBitmap?,
    ttl: String,
    canFree: Boolean,
    onLive: () -> Unit,
    onFree: () -> Unit,
) {
    Card(Modifier.padding(4.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text(device.serial, style = MaterialTheme.typography.subtitle1)
            val holder = device.lease?.owner
            Text(if (holder == null) "free" else "$holder · $ttl",
                style = MaterialTheme.typography.caption)
            if (thumb != null) {
                Image(thumb, contentDescription = "screen of ${device.serial}",
                    modifier = Modifier.fillMaxWidth())
            } else {
                Text("no screenshot", style = MaterialTheme.typography.caption)
            }
            Row {
                Button(onClick = onLive) { Text("Live view") }
                if (canFree && device.lease != null) TextButton(onClick = onFree) { Text("Force free") }
            }
        }
    }
}

@Composable
private fun QueuePanel(queue: List<QueueEntry>, modifier: Modifier) {
    Column(modifier.padding(8.dp)) {
        Text("Queue", style = MaterialTheme.typography.h6)
        if (queue.isEmpty()) {
            Text("empty", style = MaterialTheme.typography.caption)
        } else {
            LazyColumn {
                items(queue, key = { it.position }) { q ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("#${q.position} ${q.owner}", style = MaterialTheme.typography.body2)
                        Text("${q.selector} · waited ${q.waitedSeconds}s · deadline ${q.deadlineIn}s",
                            style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
    }
}

internal fun ttlLeft(device: DeviceStatus, now: Long): String {
    val expiresAt = device.lease?.expiresAt ?: return "free"   // epoch seconds
    val nowSec = now / 1000                                     // UiStore.now is epoch millis
    val seconds = expiresAt - nowSec
    return if (seconds <= 0) "expired" else "ttl ${seconds}s"
}

@Composable
private fun ByteArray?.toImage(): ImageBitmap? =
    this?.let { remember(it) { runCatching { it.decodeToImageBitmap() }.getOrNull() } }

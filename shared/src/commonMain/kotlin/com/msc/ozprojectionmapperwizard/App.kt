package com.msc.ozprojectionmapperwizard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.msc.ozprojectionmapperwizard.controller.DiscoveredPlayer
import com.msc.ozprojectionmapperwizard.controller.KnownPlayer
import com.msc.ozprojectionmapperwizard.controller.KnownPlayers
import com.msc.ozprojectionmapperwizard.controller.PlayerAddress
import com.msc.ozprojectionmapperwizard.controller.PlayerClient
import com.msc.ozprojectionmapperwizard.controller.PlayerException
import com.msc.ozprojectionmapperwizard.controller.RenderException
import com.msc.ozprojectionmapperwizard.controller.SendProgress
import com.msc.ozprojectionmapperwizard.controller.SendVideo
import com.msc.ozprojectionmapperwizard.core.protocol.DeviceInfo
import com.msc.ozprojectionmapperwizard.core.protocol.PlayReason
import com.msc.ozprojectionmapperwizard.controller.AppUpdates
import com.msc.ozprojectionmapperwizard.core.update.Releases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(platform: ControllerPlatform) {
    val known = remember { KnownPlayers(platform.store) }
    var openDeviceId by remember { mutableStateOf<String?>(null) }
    var aligning by remember { mutableStateOf(false) }
    var editingContent by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp)) {
                    val player = openDeviceId?.let { id -> known.players.collectAsState().value.firstOrNull { it.deviceId == id } }
                    when {
                        player == null -> ProjectorList(platform, known, onOpen = { openDeviceId = it.deviceId })
                        aligning -> MappingEditor(player, onDone = { aligning = false })
                        editingContent && platform.renderer != null ->
                            ContentEditor(platform, platform.renderer!!, player, onDone = { editingContent = false })
                        else -> ProjectorScreen(
                            platform, known, player,
                            onBack = { openDeviceId = null },
                            onAlign = { aligning = true },
                            onContent = { editingContent = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectorList(platform: ControllerPlatform, known: KnownPlayers, onOpen: (KnownPlayer) -> Unit) {
    val paired by known.players.collectAsState()
    val discovered by remember { platform.discovery.players() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<PlayerAddress?>(null) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // A found projector is either one we already paired (maybe at a new IP) or a new one needing the PIN.
    fun connect(address: PlayerAddress) {
        error = null
        busy = "Connecting to $address…"
        scope.launch {
            try {
                val info = PlayerClient(address).info()
                val existing = known.byId(info.deviceId)
                if (existing != null) {
                    if (existing.address != address) known.moved(info.deviceId, address)
                    onOpen(existing)
                } else {
                    pairing = address
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e, address)
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Projectors", style = MaterialTheme.typography.headlineMedium)

        if (paired.isNotEmpty()) {
            Text("Paired", style = MaterialTheme.typography.titleMedium)
            paired.forEach { player ->
                ListCard(player.name, player.address.toString()) { onOpen(player) }
            }
        }

        val pairedHosts = paired.map { it.address }.toSet()
        val fresh = discovered.filter { it.address !in pairedHosts }
        Text("Found on this Wi-Fi", style = MaterialTheme.typography.titleMedium)
        if (fresh.isEmpty()) {
            Text(
                "Searching… Open the Oz Player app on the projector and keep both devices on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        fresh.forEach { found: DiscoveredPlayer ->
            ListCard(found.serviceName, found.address.toString()) { connect(found.address) }
        }

        HorizontalDivider()
        Text("Or type the address shown on the projector", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("IP address") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { PlayerAddress.parse(typed)?.let(::connect) ?: run { error = "That doesn't look like an address." } },
                enabled = busy == null,
            ) { Text("Connect") }
        }

        busy?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        HorizontalDivider()
        UpdatesCard(platform)
    }

    pairing?.let { address ->
        PinDialog(
            address = address,
            onPair = { pin ->
                val player = known.pair(address, pin, platform.deviceName)
                pairing = null
                onOpen(player)
            },
            onDismiss = { pairing = null },
        )
    }
}

@Composable
private fun PinDialog(address: PlayerAddress, onPair: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with projector") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the 6-digit PIN shown on the projector at $address. If it is playing a show, press an arrow key on its remote to show the PIN.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = pin.length == 6 && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onPair(pin)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = describe(e, address)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Pairing…" else "Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProjectorScreen(
    platform: ControllerPlatform,
    known: KnownPlayers,
    player: KnownPlayer,
    onBack: () -> Unit,
    onAlign: () -> Unit,
    onContent: () -> Unit,
) {
    val client = remember(player) { player.client() }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<SendProgress?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var watchingUpdate by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun act(label: String, action: suspend () -> DeviceInfo?) {
        if (busy) return
        busy = true
        error = null
        notice = null
        status = label
        scope.launch {
            try {
                action()?.let { info = it }
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = null
                error = describe(e, player.address)
            } finally {
                busy = false
                progress = null
            }
        }
    }

    LaunchedEffect(player) { act("Connecting…") { client.info() } }

    // While the projector updates itself, show its progress until it restarts or finishes.
    LaunchedEffect(watchingUpdate) {
        if (!watchingUpdate) return@LaunchedEffect
        repeat(90) {
            delay(2_000)
            val latest = runCatching { client.info() }.getOrNull()
            if (latest != null) info = latest
            if (latest != null && latest.updateStatus == null) {
                watchingUpdate = false
                return@LaunchedEffect
            }
        }
        watchingUpdate = false
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Projectors") }
        }
        Text(player.name, style = MaterialTheme.typography.headlineMedium)
        Text(player.address.toString(), style = MaterialTheme.typography.bodyMedium)

        info?.let { device ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Now: ${nowPlayingText(device)}", fontWeight = FontWeight.SemiBold)
                    Text("${device.model} · ${device.displayWidth}×${device.displayHeight} · Android ${device.osVersion}")
                    Text("Free space: ${formatBytes(device.freeBytes)}")
                }
            }
        }

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                act("Reading the video…") {
                    val video = platform.pickVideo() ?: return@act null
                    status = "Sending ${video.name}…"
                    SendVideo.send(client, video, platform.clock()) { progress = it }
                }
            },
        ) { Text("Send a video to loop") }

        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = onAlign) {
            Text("Align mapping live on the wall")
        }

        if (platform.renderer != null) {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = onContent) {
                Text("Put videos, GIFs or images on the surfaces")
            }
        } else {
            Text("To put videos, GIFs or images on the surfaces, use the PC app for now.", style = MaterialTheme.typography.bodySmall)
        }

        progress?.let { p ->
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            Text("${formatBytes(p.sentBytes)} of ${formatBytes(p.totalBytes)}")
        }
        status?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        info?.let { device ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Projector app: build ${device.appBuild}", modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !busy, onClick = {
                    act("Checking for a newer projector app…") {
                        val update = Releases.available(AppUpdates().releases(), Releases.App.PLAYER, device.appBuild, includeTestBuilds(platform.store))
                        if (update == null) {
                            notice = "The projector app is up to date."
                            null
                        } else {
                            notice = "The projector is getting build ${update.build}. When it asks, press OK on its remote to install."
                            client.updateApp(update.downloadUrl, update.build).also { watchingUpdate = true }
                        }
                    }
                }) { Text("Update projector app") }
            }
            device.updateStatus?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            notice?.let { Text(it) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { act("Going black…") { client.black() } }) { Text("Black") }
            OutlinedButton(enabled = !busy, onClick = { act("Resuming…") { client.resume() } }) { Text("Resume schedule") }
            OutlinedButton(enabled = !busy, onClick = { act("Refreshing…") { client.info() } }) { Text("Refresh") }
        }

        info?.shows?.takeIf { it.isNotEmpty() }?.let { shows ->
            Text("Shows on the projector", style = MaterialTheme.typography.titleMedium)
            shows.forEach { show ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(show.name, fontWeight = FontWeight.SemiBold)
                            Text(show.fileName, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = !busy, onClick = { act("Starting ${show.name}…") { client.play(show.id) } }) { Text("Play") }
                        TextButton(enabled = !busy, onClick = { act("Removing ${show.name}…") { client.removeShow(show.id) } }) { Text("Remove") }
                    }
                }
            }
        }

        Spacer(Modifier.padding(8.dp))
        TextButton(onClick = { confirmForget = true }) { Text("Forget this projector") }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${player.name}?") },
            text = { Text("You'll need the projector's PIN to control it again; press an arrow key on its remote to show it. Its videos stay on it.") },
            confirmButton = {
                Button(onClick = {
                    confirmForget = false
                    onBack()
                    known.forget(player.deviceId)
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun nowPlayingText(info: DeviceInfo): String {
    val name = info.shows.firstOrNull { it.id == info.nowPlaying.showId }?.name
    return when (info.nowPlaying.reason) {
        PlayReason.SCHEDULE -> "playing ${name ?: "a show"}"
        PlayReason.OVERRIDE -> "playing ${name ?: "a show"} (chosen by hand)"
        PlayReason.OFF_HOURS -> "black (off hours)"
        PlayReason.BLACK -> "black"
        PlayReason.NO_SHOW -> "waiting for a show"
    }
}

internal fun describe(e: Exception, address: PlayerAddress): String = when (e) {
    is PlayerException -> e.message ?: "The projector refused (${e.status})"
    is RenderException -> e.message ?: "The render failed."
    else -> "Can't reach the projector at $address. Check it's on and on the same Wi-Fi as this device. (${e.message ?: e::class.simpleName})"
}

internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = if (unit == 0) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
    return "$rounded ${units[unit]}"
}

package com.msc.ozprojectionmapperwizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.msc.ozprojectionmapperwizard.controller.BlenderImport
import com.msc.ozprojectionmapperwizard.controller.Content
import com.msc.ozprojectionmapperwizard.controller.KnownPlayer
import com.msc.ozprojectionmapperwizard.controller.Mapping
import com.msc.ozprojectionmapperwizard.controller.MediaLibrary
import com.msc.ozprojectionmapperwizard.controller.SendVideo
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.MediaKind
import com.msc.ozprojectionmapperwizard.core.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Choose what plays on each surface, then render it on this device and send it to loop on the projector. */
@Composable
fun ContentEditor(
    platform: ControllerPlatform,
    renderer: ShowRenderer,
    player: KnownPlayer,
    onAlign: (surfaceId: String) -> Unit,
    onDone: () -> Unit,
) {
    val client = remember(player) { player.client() }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<Project?>(null) }
    var lengthText by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<RenderStep?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pickingFace by remember { mutableStateOf<String?>(null) }
    var addingSurface by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        try {
            val loaded = Mapping.forPlayer(client.project(), client.info())
            project = loaded
            lengthText = formatSeconds(loaded.scene.durationSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e, player.address)
        }
    }

    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e, player.address)
            } finally {
                busy = false
                step = null
            }
        }
    }

    fun withLength(p: Project): Project {
        val seconds = lengthText.replace(',', '.').toDoubleOrNull() ?: return p
        return Content.setDuration(p, seconds)
    }

    val picking = pickingFace
    val forPicker = project
    if (picking != null && forPicker != null) {
        MediaPicker(
            renderer, forPicker, player.deviceId, picking,
            onPick = { media ->
                val updated = Content.setMedia(forPicker, picking, media)
                // Until the length is changed by hand, it follows the longest video.
                if (lengthText.isBlank() || forPicker.scene.cues.isEmpty()) lengthText = formatSeconds(Content.suggestedDuration(updated))
                project = updated
                pickingFace = null
            },
            onCancel = { pickingFace = null },
        )
        return
    }
    if (addingSurface && forPicker != null) {
        MediaPicker(
            renderer, forPicker, player.deviceId, faceId = null,
            onPick = { media ->
                addingSurface = false
                work {
                    val (added, surfaceId) = Mapping.addSurfaceFor(forPicker, player.deviceId, media)
                    if (lengthText.isBlank() || forPicker.scene.cues.isEmpty()) lengthText = formatSeconds(Content.suggestedDuration(added))
                    val ready = withLength(added)
                    project = ready
                    step = RenderStep("Saving on the projector…")
                    client.saveProject(ready)
                    // Straight on to the wall, with the new surface picked, to drag it into place.
                    onAlign(surfaceId)
                }
            },
            onCancel = { addingSurface = false },
        )
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onDone, enabled = !busy) { Text("‹ ${player.name}") }
        Text("Videos on surfaces", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose a video, GIF or image for each surface. This computer then warps them onto your mapping as one video and sends it to the projector to loop.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val current = project
        if (current == null) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: Text("Loading the mapping…")
            return@Column
        }
        val mapping = Mapping.projector(current, player.deviceId)
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            work {
                val loaded = renderer.importBlender { step = it } ?: return@work
                val updated = BlenderImport.apply(current, player.deviceId, loaded)
                project = updated
                lengthText = formatSeconds(updated.scene.durationSeconds)
                step = RenderStep("Saving on the projector…")
                client.saveProject(updated)
                notice = "Imported ${loaded.export.surfaces.size} surfaces from Blender. Drag their corners into place in \"Align mapping live on the wall\", then come back here to render."
            }
        }) { Text("Import surfaces from Blender…") }
        OutlinedButton(
            enabled = !busy && mapping.surfaces.size < Mapping.MAX_SURFACES,
            modifier = Modifier.fillMaxWidth(),
            onClick = { addingSurface = true },
        ) { Text("New surface from an image…") }
        if (mapping.surfaces.isEmpty()) {
            Text("There are no surfaces yet. Make one from an image, import them from Blender, or add them in \"Align mapping live on the wall\".")
        }

        if (mapping.surfaces.isNotEmpty()) {
            TextButton(enabled = !busy, onClick = {
                work {
                    val ready = withLength(current)
                    project = ready
                    client.saveProject(ready)
                    onAlign(mapping.surfaces.first().id)
                }
            }) { Text("Move surfaces on the wall…") }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { filling = !filling }) {
                Text(if (filling) "Done filling" else "Fill every surface from a group…")
            }
            if (filling) {
                val faceIds = mapping.surfaces.map { it.faceId }
                FillFromGroup(renderer.library, enabled = !busy) { files, order ->
                    val updated = Content.fill(current, faceIds, files, order)
                    if (lengthText.isBlank() || current.scene.cues.isEmpty()) lengthText = formatSeconds(Content.suggestedDuration(updated))
                    project = updated
                }
            }
        }

        mapping.surfaces.forEach { surface ->
            val face = current.scene.faces.firstOrNull { it.id == surface.faceId }
            val media = Content.mediaFor(current, surface.faceId)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (media != null) MediaThumbnail(renderer, media, Modifier.width(96.dp).padding(vertical = 8.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(face?.name ?: surface.id, fontWeight = FontWeight.SemiBold)
                        Text(media?.let(::describeMedia) ?: "Black", style = MaterialTheme.typography.bodySmall)
                        if (media != null && !renderer.hasMedia(media.id)) {
                            Text("This file isn't on this computer. Choose it again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(enabled = !busy, onClick = { pickingFace = surface.faceId }) { Text("Choose…") }
                    if (media != null) {
                        TextButton(enabled = !busy, onClick = { project = Content.setMedia(current, surface.faceId, null) }) { Text("Clear") }
                        if (mapping.surfaces.size > 1) {
                            TextButton(enabled = !busy, onClick = {
                                project = Content.setMediaOnAll(current, mapping.surfaces.map { it.faceId }, media)
                            }) { Text("Use on all") }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lengthText,
                onValueChange = { lengthText = it },
                label = { Text("Show length (seconds)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(enabled = !busy && current.scene.cues.isNotEmpty(), onClick = {
                lengthText = formatSeconds(Content.suggestedDuration(current))
            }) { Text("Fit longest video") }
        }
        Text("The whole show loops at this length; shorter videos and GIFs repeat within it.", style = MaterialTheme.typography.bodySmall)

        val missing = current.media.filterNot { renderer.hasMedia(it.id) }
        Button(
            enabled = !busy && current.scene.cues.isNotEmpty() && missing.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                work {
                    val ready = withLength(current)
                    project = ready
                    lengthText = formatSeconds(ready.scene.durationSeconds)
                    step = RenderStep("Saving on the projector…")
                    client.saveProject(ready)
                    val video = renderer.render(ready, Mapping.projector(ready, player.deviceId)) { step = it }
                    SendVideo.sendRender(client, video, ready.scene.durationSeconds, platform.clock()) { p ->
                        step = RenderStep("Sending to the projector: ${formatBytes(p.sentBytes)} of ${formatBytes(p.totalBytes)}", p.fraction)
                    }
                    notice = "Sent. The projector is looping the new show."
                }
            },
        ) { Text("Render and send to projector") }

        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            work {
                val ready = withLength(current)
                project = ready
                client.saveProject(ready)
                notice = "Saved on the projector."
            }
        }) { Text("Save without rendering") }

        step?.let { s ->
            s.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(s.text)
        }
        notice?.let { Text(it, fontWeight = FontWeight.SemiBold) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * Pick a group (or the whole library) and hand its files out to every surface, in order or
 * shuffled. Shuffling again deals a new mix, to try looks quickly.
 */
@Composable
private fun FillFromGroup(library: MediaLibrary, enabled: Boolean, onFill: (List<MediaItem>, Content.FillOrder) -> Unit) {
    val allItems by library.items.collectAsState()
    val groups by library.groups.collectAsState()
    var groupId by remember { mutableStateOf<String?>(groups.firstOrNull()?.id) }
    val files = library.itemsIn(groupId.takeIf { id -> groups.any { it.id == id } })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose a group, then hand its files out to the surfaces. Shuffle again for a new mix.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = groupId == null, onClick = { groupId = null }, label = { Text("All (${allItems.size})") })
                groups.forEach { g ->
                    FilterChip(
                        selected = g.id == groupId,
                        onClick = { groupId = g.id },
                        label = { Text("${g.name} (${g.mediaIds.count { id -> allItems.any { it.id == id } }})") },
                    )
                }
            }
            if (files.isEmpty()) {
                Text("This group has no files yet. Add some with \"Choose…\" on a surface.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && files.isNotEmpty(), onClick = { onFill(files, Content.FillOrder.SHUFFLED) }) { Text("Shuffle") }
                OutlinedButton(enabled = enabled && files.isNotEmpty(), onClick = { onFill(files, Content.FillOrder.IN_ORDER) }) { Text("In order") }
            }
        }
    }
}

internal fun describeMedia(media: MediaItem): String {
    val kind = when {
        media.kind == MediaKind.IMAGE -> "Image"
        media.fileName.endsWith(".gif", ignoreCase = true) -> "GIF"
        else -> "Video"
    }
    val length = media.durationSeconds?.let { " · ${formatSeconds(it)} s" }.orEmpty()
    return "${media.fileName} · $kind ${media.width}×${media.height}$length"
}

private fun formatSeconds(seconds: Double): String {
    val tenths = (seconds * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

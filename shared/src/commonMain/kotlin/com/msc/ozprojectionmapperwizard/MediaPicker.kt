package com.msc.ozprojectionmapperwizard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msc.ozprojectionmapperwizard.controller.Content
import com.msc.ozprojectionmapperwizard.controller.Mapping
import com.msc.ozprojectionmapperwizard.core.model.MediaItem
import com.msc.ozprojectionmapperwizard.core.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The media library as a grid of previews, sorted into groups. Tapping one shows the whole
 * mapping with it on [faceId], so the choice can be judged on the object before it is used. With
 * no [faceId] the pick makes a new surface shaped like the file instead.
 */
@Composable
fun MediaPicker(
    renderer: ShowRenderer,
    project: Project,
    projectorId: String,
    faceId: String?,
    onPick: (MediaItem) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val library = renderer.library
    val allItems by library.items.collectAsState()
    val groups by library.groups.collectAsState()
    var groupId by remember { mutableStateOf<String?>(null) }
    val group = groups.firstOrNull { it.id == groupId }
    val items = if (group == null) allItems else group.mediaIds.toSet().let { ids -> allItems.filter { it.id in ids } }
    val faceName = faceId?.let { id -> project.scene.faces.firstOrNull { it.id == id }?.name } ?: "this surface"
    var selected by remember { mutableStateOf(faceId?.let { Content.mediaFor(project, it) }) }
    var step by remember { mutableStateOf<RenderStep?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Typing a name for a new group, or a new name for the open one.
    var naming by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val output = Mapping.projector(project, projectorId).output
    val full = faceId == null && Mapping.projector(project, projectorId).surfaces.size >= Mapping.MAX_SURFACES

    fun candidate(media: MediaItem): Project =
        if (faceId != null) Content.setMedia(project, faceId, media)
        else if (full) project
        else Mapping.addSurfaceFor(project, projectorId, media).first

    // How the whole mapping looks with the selected file on this surface, or on its new surface.
    val preview by produceState<Result<ImageBitmap>?>(null, selected) {
        val media = selected ?: return@produceState
        value = null
        value = try {
            val c = candidate(media)
            Result.success(decode(renderer.preview(c, Mapping.projector(c, projectorId))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addFiles() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                renderer.addToLibrary(groupId) { step = it }
                library.items.value.firstOrNull()?.let { if (selected == null) selected = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't add those files."
            } finally {
                busy = false
                step = null
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onCancel, enabled = !busy) { Text("‹ Back without changing") }
        Text(if (faceId == null) "New surface from a file" else "Choose for $faceName", style = MaterialTheme.typography.headlineMedium)
        if (faceId == null) {
            Text(
                "Pick an image or video. You get a new surface with the same shape, already showing it, to drag into place on the wall.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (full) Text("This projector already has ${Mapping.MAX_SURFACES} surfaces. Remove one first.", color = MaterialTheme.colorScheme.error)
        }

        val chosen = selected
        if (chosen != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(output.width.toFloat() / output.height).background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        val result = preview
                        when {
                            result == null -> Text("Making a preview on the mapping…", color = Color.White)
                            result.isFailure -> Text("Couldn't make a preview: ${result.exceptionOrNull()?.message}", color = Color.White)
                            else -> Image(result.getOrThrow(), contentDescription = "Preview of the mapping", modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(describeMedia(chosen), fontWeight = FontWeight.SemiBold)
                    Text("Shape ${describeAspect(chosen)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && !full && renderer.hasMedia(chosen.id), onClick = { onPick(chosen) }) {
                            Text(if (faceId == null) "Make this surface" else "Use on $faceName")
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            library.remove(chosen.id)
                            selected = null
                        }) { Text("Remove from library") }
                    }
                    if (!renderer.hasMedia(chosen.id)) {
                        Text("This file isn't on this computer any more.", color = MaterialTheme.colorScheme.error)
                    }
                    if (groups.isNotEmpty()) {
                        Text("In groups (tap to change):", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            groups.forEach { g ->
                                val inGroup = chosen.id in g.mediaIds
                                FilterChip(
                                    selected = inGroup,
                                    enabled = !busy,
                                    onClick = {
                                        if (inGroup) library.removeFromGroup(g.id, chosen.id) else library.addToGroup(g.id, listOf(chosen.id))
                                    },
                                    label = { Text(g.name) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Text("Groups", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = groupId == null, onClick = { groupId = null; renaming = false }, label = { Text("All (${allItems.size})") })
            groups.forEach { g ->
                FilterChip(
                    selected = g.id == groupId,
                    onClick = { groupId = g.id; renaming = false; naming = null },
                    label = { Text("${g.name} (${g.mediaIds.count { id -> allItems.any { it.id == id } }})") },
                )
            }
            TextButton(enabled = !busy, onClick = { naming = ""; renaming = false }) { Text("+ New group") }
        }
        naming?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { naming = it },
                    label = { Text(if (renaming) "New name" else "Group name, like \"Garage door\"") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val open = groupId
                    if (renaming && open != null) {
                        library.renameGroup(open, name)
                    } else {
                        val id = library.createGroup(name)
                        // A file picked in "All" goes straight into the group it was being looked at for.
                        selected?.let { if (open == null) library.addToGroup(id, listOf(it.id)) }
                        groupId = id
                    }
                    naming = null
                    renaming = false
                }) { Text(if (renaming) "Rename" else "Create") }
                TextButton(onClick = { naming = null; renaming = false }) { Text("Cancel") }
            }
        }
        if (group != null && naming == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { naming = group.name; renaming = true }) { Text("Rename group") }
                TextButton(enabled = !busy, onClick = { library.deleteGroup(group.id); groupId = null }) { Text("Delete group (keeps the files)") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group?.let { "${it.name} (${items.size})" } ?: "Library (${items.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(enabled = !busy, onClick = ::addFiles) { Text(if (group == null) "Add files…" else "Add files to ${group.name}…") }
        }
        step?.let { s ->
            s.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(s.text)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            allItems.isEmpty() ->
                Text("Your library is empty. Press \"Add files…\" and pick as many videos, GIFs and images as you like; hold Ctrl or Shift to pick several.")
            items.isEmpty() ->
                Text("This group is empty. Add files to it here, or open a file in \"All\" and tap this group's name under it.")
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 150.dp).toInt().coerceAtLeast(2)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { media ->
                            val isSelected = media.id == selected?.id
                            Column(
                                Modifier
                                    .weight(1f)
                                    .border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable(enabled = !busy) { selected = media }
                                    .padding(4.dp),
                            ) {
                                MediaThumbnail(renderer, media, Modifier.fillMaxWidth())
                                Text(media.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(describeAspect(media), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // Keep the last row's cells the same width as the others.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The shape of [media] as a short ratio like "16:9", or "1.85:1" when it isn't a tidy one. */
internal fun describeAspect(media: MediaItem): String {
    if (media.width <= 0 || media.height <= 0) return "unknown"
    val ratio = media.width.toDouble() / media.height
    for (h in 1..10) {
        val w = ratio * h
        val whole = w.roundToInt()
        if (whole in 1..40 && abs(w - whole) < 0.02 * h) return "$whole:$h"
    }
    val hundredths = (ratio * 100).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}:1"
}

/** A small preview of [media], letterboxed in a 16:9 box. */
@Composable
fun MediaThumbnail(renderer: ShowRenderer, media: MediaItem, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, media.id) {
        value = try {
            renderer.thumbnail(media)?.let(::decode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    Box(modifier.aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = media.fileName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(if (renderer.hasMedia(media.id)) "…" else "Missing", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
private fun decode(png: ByteArray): ImageBitmap = png.decodeToImageBitmap()

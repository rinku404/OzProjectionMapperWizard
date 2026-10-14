package com.msc.ozprojectionmapperwizard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * The media library as a grid of previews. Tapping one shows the whole mapping with it on
 * [faceId], so the choice can be judged on the object before it is used.
 */
@Composable
fun MediaPicker(
    renderer: ShowRenderer,
    project: Project,
    projectorId: String,
    faceId: String,
    onPick: (MediaItem) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by renderer.library.items.collectAsState()
    val faceName = project.scene.faces.firstOrNull { it.id == faceId }?.name ?: "this surface"
    var selected by remember { mutableStateOf(Content.mediaFor(project, faceId)) }
    var step by remember { mutableStateOf<RenderStep?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val output = Mapping.projector(project, projectorId).output

    // How the whole mapping looks with the selected file on this surface.
    val preview by produceState<Result<ImageBitmap>?>(null, selected) {
        val media = selected ?: return@produceState
        value = null
        value = try {
            val candidate = Content.setMedia(project, faceId, media)
            Result.success(decode(renderer.preview(candidate, Mapping.projector(candidate, projectorId))))
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
                renderer.addToLibrary { step = it }
                renderer.library.items.value.firstOrNull()?.let { if (selected == null) selected = it }
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
        Text("Choose for $faceName", style = MaterialTheme.typography.headlineMedium)

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && renderer.hasMedia(chosen.id), onClick = { onPick(chosen) }) { Text("Use on $faceName") }
                        OutlinedButton(enabled = !busy, onClick = {
                            renderer.library.remove(chosen.id)
                            selected = null
                        }) { Text("Remove from library") }
                    }
                    if (!renderer.hasMedia(chosen.id)) {
                        Text("This file isn't on this computer any more.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library (${items.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(enabled = !busy, onClick = ::addFiles) { Text("Add files…") }
        }
        step?.let { s ->
            s.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(s.text)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (items.isEmpty()) {
            Text("Your library is empty. Press \"Add files…\" and pick as many videos, GIFs and images as you like; hold Ctrl or Shift to pick several.")
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

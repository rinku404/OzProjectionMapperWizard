package com.msc.ozprojectionmapperwizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msc.ozprojectionmapperwizard.controller.KnownPlayer
import com.msc.ozprojectionmapperwizard.controller.Mapping
import com.msc.ozprojectionmapperwizard.core.geometry.Homography
import com.msc.ozprojectionmapperwizard.core.geometry.Vec2
import com.msc.ozprojectionmapperwizard.core.model.Project
import com.msc.ozprojectionmapperwizard.core.model.ProjectorMapping
import com.msc.ozprojectionmapperwizard.core.protocol.LiveState
import com.msc.ozprojectionmapperwizard.core.protocol.TestPattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val CORNER_NAMES = listOf("Top left", "Top right", "Bottom right", "Bottom left")
private val CORNER_ARROWS = listOf("↖", "↗", "↘", "↙")

/** Live alignment: drag surfaces and corners here and the projector redraws them on the wall. */
@Composable
fun MappingEditor(player: KnownPlayer, onDone: () -> Unit) {
    val client = remember(player) { player.client() }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<Project?>(null) }
    var selectedSurface by remember { mutableStateOf<String?>(null) }
    var selectedCorner by remember { mutableStateOf<Int?>(null) }
    var pattern by remember { mutableStateOf(TestPattern.GRID) }
    var step by remember { mutableStateOf(1.0) }
    var liveStatus by remember { mutableStateOf("Connecting…") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val liveStates = remember { MutableStateFlow<LiveState?>(null) }

    LaunchedEffect(player) {
        try {
            val loaded = Mapping.forPlayer(client.project(), client.info())
            project = loaded
            selectedSurface = Mapping.projector(loaded, player.deviceId).surfaces.firstOrNull()?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e, player.address)
        }
    }

    // Every edit becomes the next live frame; the link sends the newest one.
    LaunchedEffect(project, pattern, selectedSurface, selectedCorner) {
        project?.let { liveStates.value = Mapping.liveState(it, player.deviceId, pattern, selectedSurface, selectedCorner) }
    }

    // Keeps the projector in live mode while this screen is open, reconnecting after Wi-Fi blips.
    LaunchedEffect(player) {
        while (true) {
            try {
                liveStatus = "Live on the projector"
                client.live(liveStates.filterNotNull())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                liveStatus = "${describe(e, player.address)} Retrying…"
                delay(2_000)
            }
        }
    }

    fun edit(change: (Project) -> Project) {
        project = project?.let(change)
    }

    fun finish() {
        val current = project ?: return onDone()
        saving = true
        scope.launch {
            try {
                client.saveProject(current)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't save the mapping on the projector. ${describe(e, player.address)}"
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text("‹ Back without saving") }
            Button(onClick = ::finish, enabled = !saving && project != null) { Text(if (saving) "Saving…" else "Save and finish") }
        }
        Text("Align mapping", style = MaterialTheme.typography.headlineMedium)
        Text(liveStatus, style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val current = project ?: return@Column Text("Loading the mapping…")
        val mapping = Mapping.projector(current, player.deviceId)

        MappingCanvas(
            project = current,
            mapping = mapping,
            selectedSurface = selectedSurface,
            selectedCorner = selectedCorner,
            onSelect = { surface, corner ->
                selectedSurface = surface
                selectedCorner = corner
            },
            onMoveCorner = { surface, corner, to -> edit { Mapping.moveCorner(it, player.deviceId, surface, corner, to) } },
            onMoveSurface = { surface, delta -> edit { Mapping.moveSurface(it, player.deviceId, surface, delta) } },
        )
        Text(
            "Drag a corner to move it, or drag inside a surface to move all of it. Use the arrows below for the last few pixels.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestPattern.entries.forEach { p ->
                val label = when (p) {
                    TestPattern.GRID -> "Grid"
                    TestPattern.WHITE -> "White"
                    TestPattern.OUTLINE -> "Outline"
                }
                if (p == pattern) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { pattern = p }) { Text(label) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = mapping.surfaces.size < Mapping.MAX_SURFACES,
                onClick = {
                    val (updated, id) = Mapping.addSurface(current, player.deviceId)
                    project = updated
                    selectedSurface = id
                    selectedCorner = null
                },
            ) { Text("Add surface") }
            val selected = selectedSurface
            if (selected != null) {
                OutlinedButton(onClick = {
                    edit { Mapping.removeSurface(it, player.deviceId, selected) }
                    selectedSurface = null
                    selectedCorner = null
                }) { Text("Remove surface") }
            }
        }

        val surface = mapping.surfaces.firstOrNull { it.id == selectedSurface }
        if (surface != null) {
            val face = current.scene.faces.firstOrNull { it.id == surface.faceId }
            OutlinedTextField(
                value = face?.name.orEmpty(),
                onValueChange = { name -> edit { Mapping.renameFace(it, surface.faceId, name) } },
                label = { Text("Surface name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Fine-tune a corner", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CORNER_ARROWS.forEachIndexed { i, name ->
                    if (i == selectedCorner) Button(onClick = {}) { Text(name) }
                    else OutlinedButton(onClick = { selectedCorner = i }) { Text(name) }
                }
            }
            val corner = selectedCorner
            if (corner != null) {
                val at = Mapping.corners(surface)[corner]
                fun nudge(dx: Double, dy: Double) =
                    edit { Mapping.moveCorner(it, player.deviceId, surface.id, corner, Vec2(at.x + dx * step, at.y + dy * step)) }
                Text("${CORNER_NAMES[corner]} at ${at.x.toInt()}, ${at.y.toInt()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nudge(-1.0, 0.0) }) { Text("←") }
                    OutlinedButton(onClick = { nudge(0.0, -1.0) }) { Text("↑") }
                    OutlinedButton(onClick = { nudge(0.0, 1.0) }) { Text("↓") }
                    OutlinedButton(onClick = { nudge(1.0, 0.0) }) { Text("→") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0, 10.0).forEach { s ->
                        val label = "${s.toInt()} px"
                        if (s == step) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { step = s }) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingCanvas(
    project: Project,
    mapping: ProjectorMapping,
    selectedSurface: String?,
    selectedCorner: Int?,
    onSelect: (String?, Int?) -> Unit,
    onMoveCorner: (String, Int, Vec2) -> Unit,
    onMoveSurface: (String, Vec2) -> Unit,
) {
    val output = mapping.output
    val touchRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    // The gesture handlers outlive recompositions; these always hold the newest values.
    val latest by rememberUpdatedState(mapping)
    val latestSelected by rememberUpdatedState(selectedSurface)

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(output.width.toFloat() / output.height)
            .background(Color.Black)
            .pointerInput(output) {
                detectTapGestures { offset ->
                    val scale = size.width.toDouble() / output.width
                    val p = Vec2(offset.x / scale, offset.y / scale)
                    val hit = Mapping.cornerAt(latest, p, touchRadius / scale, latestSelected)
                    if (hit != null) onSelect(hit.first, hit.second) else onSelect(Mapping.surfaceAt(latest, p), null)
                }
            }
            .pointerInput(output) {
                var corner: Pair<String, Int>? = null
                var surface: String? = null
                var pointer = Vec2.ZERO
                detectDragGestures(
                    onDragStart = { offset ->
                        val scale = size.width.toDouble() / output.width
                        pointer = Vec2(offset.x / scale, offset.y / scale)
                        corner = Mapping.cornerAt(latest, pointer, touchRadius / scale, latestSelected)
                        surface = if (corner == null) Mapping.surfaceAt(latest, pointer) else null
                        corner?.let { onSelect(it.first, it.second) } ?: onSelect(surface, null)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val scale = size.width.toDouble() / output.width
                        val delta = Vec2(amount.x / scale, amount.y / scale)
                        pointer += delta
                        corner?.let { (s, c) -> onMoveCorner(s, c, pointer) }
                        surface?.let { onMoveSurface(it, delta) }
                    },
                )
            },
    ) {
        val scale = size.width / output.width
        mapping.surfaces.forEachIndexed { index, surface ->
            val corners = Mapping.corners(surface)
            val selected = surface.id == selectedSurface
            val color = if (selected) Color(0xFFFFC400) else SURFACE_COLORS[index % SURFACE_COLORS.size]
            drawSurface(corners, scale, color, selected)
            val name = project.scene.faces.firstOrNull { it.id == surface.faceId }?.name ?: surface.id
            val centre = corners.reduce { a, b -> a + b } * 0.25
            val layout = textMeasurer.measure(name, TextStyle(color = Color.White, fontSize = 12.sp))
            drawText(layout, topLeft = Offset(centre.x.toFloat() * scale - layout.size.width / 2, centre.y.toFloat() * scale - layout.size.height / 2))
            corners.forEachIndexed { i, p ->
                val isSelected = selected && i == selectedCorner
                drawCircle(
                    color = if (isSelected) Color.Red else color,
                    radius = if (isSelected) 10f else 7f,
                    center = Offset(p.x.toFloat() * scale, p.y.toFloat() * scale),
                    style = if (isSelected) Stroke(3f) else androidx.compose.ui.graphics.drawscope.Fill,
                )
            }
        }
    }
}

/** Outline plus a perspective grid: straight lines stay straight under a homography, so mapping the ends is exact. */
private fun DrawScope.drawSurface(corners: List<Vec2>, scale: Float, color: Color, selected: Boolean) {
    fun Vec2.px() = Offset(x.toFloat() * scale, y.toFloat() * scale)
    val outline = Path().apply {
        moveTo(corners[0].px().x, corners[0].px().y)
        corners.drop(1).forEach { lineTo(it.px().x, it.px().y) }
        close()
    }
    drawPath(outline, color.copy(alpha = 0.18f))
    val h = runCatching { Homography.unitSquareTo(corners) }.getOrNull()
    if (h != null) {
        for (i in 1 until 8) {
            val t = i / 8.0
            drawLine(color.copy(alpha = 0.5f), h.map(Vec2(t, 0.0)).px(), h.map(Vec2(t, 1.0)).px(), 1f)
            drawLine(color.copy(alpha = 0.5f), h.map(Vec2(0.0, t)).px(), h.map(Vec2(1.0, t)).px(), 1f)
        }
    }
    drawPath(outline, color, style = Stroke(if (selected) 3f else 2f))
}

private val SURFACE_COLORS = listOf(
    Color(0xFF00C8FF), Color(0xFF50FF78), Color(0xFFFF5AC8), Color(0xFFA078FF),
    Color(0xFFFF8C3C), Color(0xFF00FFDC), Color(0xFFFFFFFF), Color(0xFF78C8FF),
)

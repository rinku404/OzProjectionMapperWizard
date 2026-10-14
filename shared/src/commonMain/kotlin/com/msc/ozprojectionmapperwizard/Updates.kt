package com.msc.ozprojectionmapperwizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msc.ozprojectionmapperwizard.controller.AppUpdates
import com.msc.ozprojectionmapperwizard.controller.KeyValueStore
import com.msc.ozprojectionmapperwizard.controller.SendProgress
import com.msc.ozprojectionmapperwizard.core.update.Releases
import com.msc.ozprojectionmapperwizard.core.update.Update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TEST_BUILDS_KEY = "updates.includeTestBuilds"

/** Test builds come from branches that aren't merged yet. */
internal fun includeTestBuilds(store: KeyValueStore) = store.get(TEST_BUILDS_KEY) == "true"

/** "Check for updates" for this app, with an option to include test builds. */
@Composable
fun UpdatesCard(platform: ControllerPlatform) {
    val scope = rememberCoroutineScope()
    var includeTest by remember { mutableStateOf(includeTestBuilds(platform.store)) }
    var message by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<Update?>(null) }
    var progress by remember { mutableStateOf<SendProgress?>(null) }
    var busy by remember { mutableStateOf(false) }
    val app = platform.updateApp

    fun run(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Couldn't update: ${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
                progress = null
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text("This app is build ${platform.appBuild}.", style = MaterialTheme.typography.bodyMedium)
            if (app == null) {
                Text("On this computer, download new versions from the GitHub releases page.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeTest, onCheckedChange = {
                    includeTest = it
                    platform.store.put(TEST_BUILDS_KEY, it.toString())
                    offer = null
                })
                Text("Include test builds (not merged yet)", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    message = "Checking…"
                    offer = null
                    run {
                        val update = Releases.available(AppUpdates().releases(), app, platform.appBuild, includeTest)
                        offer = update
                        message = if (update == null) "You have the newest build." else "Build ${update.build} is available${if (update.testBuild) " (test build)" else ""}."
                    }
                }) { Text("Check for updates") }
                offer?.let { update ->
                    Button(enabled = !busy, onClick = {
                        message = "Downloading build ${update.build}…"
                        run {
                            platform.installUpdate(update) { progress = it }
                            message = "Confirm the install when asked."
                        }
                    }) { Text("Install build ${update.build}") }
                }
            }
            progress?.let { p ->
                if (p.totalBytes > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(p.sentBytes)}${if (p.totalBytes > 0) " of ${formatBytes(p.totalBytes)}" else ""}")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

package com.msc.ozprojectionmapperwizard

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Oz Projection",
        state = rememberWindowState(width = 720.dp, height = 820.dp),
    ) {
        val platform = remember { DesktopPlatform() }
        App(platform)
    }
}

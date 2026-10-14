package com.msc.ozprojectionmapperwizard

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val platform = AndroidPlatform(this)

        // Newer Android asks before an app talks to devices on the local network.
        if (Build.VERSION.SDK_INT >= 37) {
            requestPermissions(arrayOf(LOCAL_NETWORK_PERMISSION), 1)
        }

        setContent {
            App(platform)
        }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

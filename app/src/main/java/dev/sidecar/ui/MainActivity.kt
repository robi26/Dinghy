package dev.sidecar.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sidecar.binding.core.Core
import dev.sidecar.binding.sushitrain.Sushitrain

/** A syntactically valid Syncthing device ID, used only to exercise the binding. */
private const val SAMPLE_DEVICE_ID =
    "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineSmokeTest()
                }
            }
        }
    }
}

/**
 * M0 smoke test. Beyond proving the native library loads, each line round-trips
 * real data through the binding so a stubbed or defaulted return would show up:
 * the version comes from a linker-set variable, and the device ID checks must
 * disagree with each other.
 */
@Composable
private fun EngineSmokeTest() {
    val parsed = runCatching { Sushitrain.shortDeviceID(SAMPLE_DEVICE_ID) }
        .getOrElse { "error: ${it.message}" }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sidecar", style = MaterialTheme.typography.headlineMedium)
        Text("engine: ${Sushitrain.version()}", style = MaterialTheme.typography.bodyLarge)
        Text("bridge: ${Core.coreVersion()}", style = MaterialTheme.typography.bodyMedium)
        Text("valid id accepted: ${Sushitrain.isValidDeviceID(SAMPLE_DEVICE_ID)}")
        Text("bogus id rejected: ${!Sushitrain.isValidDeviceID("NOT-A-DEVICE-ID")}")
        Text("short id: $parsed")
    }
}

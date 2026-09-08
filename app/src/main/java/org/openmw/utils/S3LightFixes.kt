package org.openmw.utils

import android.system.Os
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmw.Constants
import org.openmw.R
import org.openmw.ui.controls.UIStateManager.customColor
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.milliseconds

object S3LightFixesNative {
    init {
        System.loadLibrary("s3lightfixes")

        // Set environment variables
        val overrideFolder = File(Constants.USER_FILE_STORAGE, "OpenMW/Override")
        val cfgFolder = File(Constants.USER_FILE_STORAGE, "config")

        // Set all environment variables
        Os.setenv("S3L_DEBUG", "1", true)
        Os.setenv("S3L_NO_NOTIFICATIONS", "1", true)
        Os.setenv("RUST_BACKTRACE", "full", true)
        Os.setenv("OPENMW_OVERRIDE_DIR", overrideFolder.absolutePath, true)
        Os.setenv("OPENMW_CONFIG", cfgFolder.absolutePath, true)
        Os.setenv("USER_OPENMW_CFG", Constants.USER_OPENMW_CFG, true)
        Os.setenv("OPENMW_CFG", Constants.OPENMW_CFG, true)
    }

    @Suppress("KotlinJniMissingFunction")
    external fun run(): Int
}

@Composable
fun S3LightFixes() {
    var output by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.padding(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        runS3LightFixesDirect { commandOutput ->
                            withContext(Dispatchers.Main) {
                                output = output.plus(commandOutput).plus("\n")
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = customColor,
                    contentColor = Color.White
                )
            ) {
                Text(stringResource(R.string.run_s3lightfixes))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(8.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = output,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

suspend fun runS3LightFixesDirect(onOutputUpdate: suspend (String) -> Unit) {
    withContext(Dispatchers.IO) {
        val logcatJob = launch {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat",
                    "-s",
                    "s3lightfixes:I"
                ))
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { logLine ->
                        val message = logLine.replace(Regex("""\w/s3lightfixes\(\d+\):\s*"""), "")
                        if (message.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                onOutputUpdate(message)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Logcat reading failed, but we'll still show the result
            }
        }

        try {
            val result = S3LightFixesNative.run()
            delay(300.milliseconds)
            logcatJob.cancel()

            withContext(Dispatchers.Main) {
                onOutputUpdate("S3LightFixes Finished: $result")
            }
        } catch (e: Exception) {
            logcatJob.cancel()
            withContext(Dispatchers.Main) {
                onOutputUpdate("Error: ${e.message}")
            }
        }
    }
}
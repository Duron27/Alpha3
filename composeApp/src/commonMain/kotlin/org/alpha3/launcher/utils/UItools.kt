package org.alpha3.launcher.utils

import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.resources.backgroundbouncebw
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun BouncingBackground() {
    val image: Painter = painterResource(Res.drawable.backgroundbouncebw)
    var offset by remember { mutableStateOf(Offset.Zero) }
    var xDir by remember { mutableStateOf(1f) }
    var yDir by remember { mutableStateOf(1f) }

    val stepSize = 0.2f

    LaunchedEffect(Unit) {
        while (true) {
            offset = Offset(
                x = (offset.x + xDir * stepSize),
                y = (offset.y + yDir * stepSize)
            )
            delay(16.milliseconds)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = image,
            contentDescription = null,
            modifier = Modifier
                .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .size(2000.dp, 2337.dp)
                .scale(6f)
                .background(Color.Black)
        )
    }
}

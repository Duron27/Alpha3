package org.alpha3.launcher.globals

import androidx.compose.ui.graphics.Color

val customColor = Color(0xFF1f1e23)
val transparentBlack = Color(alpha = 0.6f, red = 0f, green = 0f, blue = 0f)
val transparent = Color(alpha = 0.0f, red = 0f, green = 0f, blue = 0f)
val darkGray = Color(alpha = 0.6f, red = 0f, green = 0f, blue = 0f)
val lightGray = Color(alpha = 0.4f, red = 0f, green = 0f, blue = 0f)

val gradientColors = listOf(
    Color(0xFF42A5F5),
    Color(0xFF478DE0),
    Color(0xFF3F76D2),
    Color(0xFF3B5FBA)
)

val editableExtensions = setOf(
    "cfg", "txt", "ini", "log",
    "glsl", "vert", "frag", "comp", "h",
    "lua", "yaml", "xml", "json", "sh"
)

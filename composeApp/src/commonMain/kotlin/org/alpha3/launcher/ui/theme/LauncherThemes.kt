package org.alpha3.launcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The launcher's themes (Settings > Appearance). Each has its look (colours, fonts, background) and its layout: how the
 * home screen's sections are reached ([NavStyle]) and how a mod row reads. Classic is the launcher as it always was
 * and the default; the others come from the mockups in handoff/ui-themes. Only the presentation changes: every
 * screen keeps its data and view models.
 */
enum class LauncherTheme(
    val id: String,
    val title: String,
    val blurb: String,
    val tokens: ThemeTokens,
) {
    Classic(
        "classic", "Classic", "The launcher as it has always been",
        ThemeTokens(
            background = Color.Black, surface = Color(0xFF1F1E23), primary = Color(0xFFC9A45C),
            secondary = Color(0xFFC9A45C), onBackground = Color.White, dim = Color(0xFFA39C8F),
            display = FontFamily.Default, body = FontFamily.Default, corner = 12,
            nav = NavStyle.Classic, backdrop = Backdrop.Classic, compactRows = false,
        )
    ),
    Hub(
        "hub", "Hub", "Gold on dark, a dashboard of tiles",
        ThemeTokens(
            background = Color(0xFF110F0D), surface = Color(0xFF1C1915), primary = Color(0xFFC9A45C),
            secondary = Color(0xFFE2C27F), onBackground = Color(0xFFEDE6D6), dim = Color(0xFFA89F8C),
            display = FontFamily.Serif, body = FontFamily.SansSerif, corner = 14,
            nav = NavStyle.Tiles, backdrop = Backdrop.Vignette, compactRows = true,
        )
    ),
    Clean(
        "clean", "Clean", "Plain and quiet: one line a mod, blue",
        ThemeTokens(
            background = Color(0xFF16171A), surface = Color(0xFF222328), primary = Color(0xFF2F6FEB),
            secondary = Color(0xFF6B9BFF), onBackground = Color(0xFFF5F5F2), dim = Color(0xFF9A9B95),
            display = FontFamily.SansSerif, body = FontFamily.SansSerif, corner = 10,
            nav = NavStyle.Segmented, backdrop = Backdrop.Flat, compactRows = true,
        )
    ),
    Rail(
        "rail", "Telvanni Rail", "Teal and purple, sections down a rail",
        ThemeTokens(
            background = Color(0xFF16121F), surface = Color(0xFF2C2342), primary = Color(0xFF5FD4C0),
            secondary = Color(0xFF8E6CE0), onBackground = Color(0xFFECE6F7), dim = Color(0xFFA99CC4),
            display = FontFamily.Serif, body = FontFamily.SansSerif, corner = 16,
            nav = NavStyle.Rail, backdrop = Backdrop.Mushroom, compactRows = true,
        )
    ),
    Terminal(
        "terminal", "Terminal", "Amber on black, all text",
        ThemeTokens(
            background = Color(0xFF0B0A06), surface = Color(0xFF2A2410), primary = Color(0xFFFFB000),
            secondary = Color(0xFFFFD066), onBackground = Color(0xFFFFB000), dim = Color(0xFFB58A2A),
            display = FontFamily.Monospace, body = FontFamily.Monospace, corner = 2,
            nav = NavStyle.Text, backdrop = Backdrop.Scanlines, compactRows = true,
        )
    ),
    Ashlands(
        "ashlands", "Ashlands", "Ember and ash, wrapping chips",
        ThemeTokens(
            background = Color(0xFF150C08), surface = Color(0xFF24140E), primary = Color(0xFFFF7A3D),
            secondary = Color(0xFFFF9C6B), onBackground = Color(0xFFF5E6DC), dim = Color(0xFFB8907C),
            display = FontFamily.Serif, body = FontFamily.SansSerif, corner = 18,
            nav = NavStyle.Chips, backdrop = Backdrop.Embers, compactRows = true,
        )
    ),
    Holo(
        "holo", "Holo HUD", "Cyan hologram, a radial menu (animated)",
        ThemeTokens(
            background = Color(0xFF02070A), surface = Color(0xFF0B2A30), primary = Color(0xFF3FF5FF),
            secondary = Color(0xFF5FB8C0), onBackground = Color(0xFFE6FEFF), dim = Color(0xFF5FB8C0),
            display = FontFamily.SansSerif, body = FontFamily.SansSerif, corner = 4,
            nav = NavStyle.Radial, backdrop = Backdrop.Holo, compactRows = true, glow = true,
        )
    ),
    Orbit(
        "orbit", "Orbit", "Sections orbit the Play core (animated)",
        ThemeTokens(
            background = Color(0xFF06040F), surface = Color(0xFF17122E), primary = Color(0xFF9B7BFF),
            secondary = Color(0xFFFF5FA2), onBackground = Color(0xFFF2EEFF), dim = Color(0xFF9C93C4),
            display = FontFamily.SansSerif, body = FontFamily.SansSerif, corner = 20,
            nav = NavStyle.Orbit, backdrop = Backdrop.Stars, compactRows = true, glow = true,
        )
    ),
    Material(
        "material", "Material Pink", "Flat dark Material with pink, like the OMW launcher",
        ThemeTokens(
            background = Color(0xFF303030), surface = Color(0xFF424242), primary = Color(0xFFFF4081),
            secondary = Color(0xFFFF80AB), onBackground = Color(0xFFFFFFFF), dim = Color(0xFFBDBDBD),
            display = FontFamily.SansSerif, body = FontFamily.SansSerif, corner = 2,
            nav = NavStyle.Underline, backdrop = Backdrop.Flat, compactRows = true,
        )
    ),
    Neon(
        "neon", "Neon Glitch", "Yellow, pink and cyan, item slots (animated)",
        ThemeTokens(
            background = Color(0xFF0A0910), surface = Color(0xFF14121C), primary = Color(0xFFFCEE0A),
            secondary = Color(0xFFFF2A6D), onBackground = Color(0xFFEDEBF5), dim = Color(0xFF8E8AA6),
            display = FontFamily.Monospace, body = FontFamily.SansSerif, corner = 0,
            nav = NavStyle.Slots, backdrop = Backdrop.Glitch, compactRows = true, glow = true,
        )
    );

    companion object {
        fun fromId(id: String?): LauncherTheme = entries.firstOrNull { it.id == id } ?: Classic
    }
}

/** How the home screen's sections (Data, Content, ... Mod tools) are reached. Classic is the swiping tab row. */
enum class NavStyle { Classic, Tiles, Segmented, Rail, Text, Chips, Radial, Orbit, Slots, Underline }

/** What the theme draws behind the launcher. Classic is the background picked in the settings, as before. */
enum class Backdrop { Classic, Flat, Vignette, Mushroom, Scanlines, Embers, Holo, Stars, Glitch }

@Immutable
data class ThemeTokens(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val onBackground: Color,
    /** secondary text: a mod's path, counts */
    val dim: Color,
    val display: FontFamily,
    val body: FontFamily,
    /** corner radius of cards and buttons, dp */
    val corner: Int,
    val nav: NavStyle,
    val backdrop: Backdrop,
    /** a mod row on one line: its name bold, its folder dim, the load order a small number */
    val compactRows: Boolean,
    val glow: Boolean = false,
)

/** The theme in use, and whether its animations are held still (Settings > Appearance > Reduce motion). */
@Immutable
data class LauncherThemeState(val theme: LauncherTheme, val reduceMotion: Boolean) {
    val tokens: ThemeTokens get() = theme.tokens
    val isClassic: Boolean get() = theme == LauncherTheme.Classic
    val animate: Boolean get() = !reduceMotion
}

val LocalLauncherTheme = staticCompositionLocalOf { LauncherThemeState(LauncherTheme.Classic, false) }

/** The Material colours of a theme other than Classic (Classic keeps the launcher's own scheme). */
internal fun ThemeTokens.colorScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = background,
    primaryContainer = primary.copy(alpha = 0.25f).compositeOver(surface),
    onPrimaryContainer = onBackground,
    secondary = secondary,
    onSecondary = background,
    secondaryContainer = secondary.copy(alpha = 0.25f).compositeOver(surface),
    onSecondaryContainer = onBackground,
    tertiary = secondary,
    onTertiary = background,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onBackground,
    surfaceVariant = surface,
    onSurfaceVariant = dim,
    outline = dim,
    outlineVariant = dim.copy(alpha = 0.4f),
    surfaceContainerLowest = background,
    surfaceContainerLow = surface.copy(alpha = 0.6f).compositeOver(background),
    surfaceContainer = surface,
    surfaceContainerHigh = surface.copy(alpha = 0.85f).compositeOver(onBackground.copy(alpha = 0.08f)),
    surfaceContainerHighest = surface.copy(alpha = 0.8f).compositeOver(onBackground.copy(alpha = 0.14f)),
    inversePrimary = primary,
)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha + background.alpha * (1f - alpha)
    if (a == 0f) return Color.Transparent
    fun mix(c: Float, b: Float) = (c * alpha + b * background.alpha * (1f - alpha)) / a
    return Color(mix(red, background.red), mix(green, background.green), mix(blue, background.blue), a)
}

/** Material's type scale in the theme's fonts: headings in the display font, the rest in the body font. */
internal fun ThemeTokens.typography(): Typography {
    val base = Typography()
    fun TextStyle.with(family: FontFamily) = copy(fontFamily = family)
    return base.copy(
        displayLarge = base.displayLarge.with(display),
        displayMedium = base.displayMedium.with(display),
        displaySmall = base.displaySmall.with(display),
        headlineLarge = base.headlineLarge.with(display),
        headlineMedium = base.headlineMedium.with(display),
        headlineSmall = base.headlineSmall.with(display),
        titleLarge = base.titleLarge.with(display),
        titleMedium = base.titleMedium.with(body),
        titleSmall = base.titleSmall.with(body),
        bodyLarge = base.bodyLarge.with(body),
        bodyMedium = base.bodyMedium.with(body),
        bodySmall = base.bodySmall.with(body),
        labelLarge = base.labelLarge.with(body),
        labelMedium = base.labelMedium.with(body),
        labelSmall = base.labelSmall.with(body),
    )
}

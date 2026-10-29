package org.alpha3.launcher.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.ui.MainScreen
import org.alpha3.launcher.ui.view.BackgroundAnimation

enum class RootDesc(
    val route: String
) {
    Main("main_screen"),
}

val LocalRootNav = staticCompositionLocalOf<NavHostController> {
    error("LocalRootNav Not Provide")
}

@Composable
fun RootNav() {
    // Switching profile changes which openmw.cfg and settings.cfg every screen reads, so the screens start afresh
    androidx.compose.runtime.key(org.alpha3.launcher.paths.Profiles.revision) {
        val navController = rememberNavController()
        CompositionLocalProvider(
            LocalRootNav provides navController,
        ) {
            RootNavHost(navController)
        }
    }
}

@Composable
fun RootNavHost(
    rootNavController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = rootNavController,
        startDestination = RootDesc.Main.route,
        modifier = modifier,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it }) + fadeIn()
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        }
    ) {
        composable(route = RootDesc.Main.route) {

            AnimatedVisibility(
                visible = !Platform.features.isProcessingMods,
                enter = fadeIn() + expandIn(),
                exit = fadeOut() + shrinkOut()
            ) {
                BackgroundAnimation()
            }
            MainScreen()
        }
    }
}

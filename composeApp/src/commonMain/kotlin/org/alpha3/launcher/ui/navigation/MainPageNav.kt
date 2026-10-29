package org.alpha3.launcher.ui.navigation

import org.jetbrains.compose.resources.StringResource
import org.alpha3.launcher.resources.home
import org.alpha3.launcher.resources.setting
import org.alpha3.launcher.resources.start_game
import org.alpha3.launcher.resources.Res
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import org.alpha3.launcher.ui.page.main.MainPage
import org.alpha3.launcher.ui.page.mod.ModAssistantViewModel
import org.alpha3.launcher.ui.page.setting.SettingPage
import org.alpha3.launcher.utils.navigateSingleTopTo

enum class MainPageDestination(
    val iconDefault: ImageVector,
    val iconSelected: ImageVector,
    val route: String,
    val labelRes: StringResource,
) {
    Home(
        Icons.Default.Home,
        Icons.Outlined.Home,
        "home",
        Res.string.home
    ),
    StartGame(
            Icons.Default.PlayArrow,
            Icons.Outlined.PlayArrow,
            "start_game",
            Res.string.start_game
        ),

    Setting(
        Icons.Default.Settings,
        Icons.Outlined.Settings,
        "setting",
        Res.string.setting
    );

    fun getIcon(selected: Boolean) : ImageVector = if (selected) iconSelected else iconDefault
}

val LocalMainPageNav = staticCompositionLocalOf<NavHostController> {
    error("LocalMainPageNav Not Provide")
}

val LocalModAssistantViewModel = staticCompositionLocalOf<ModAssistantViewModel> {
    error("LocalModAssistantViewModel Not Provide")
}

@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class, DelicateCoroutinesApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun MainPageNav(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val modAssistantViewModel = viewModel(viewModelStoreOwner = LocalViewModelStoreOwner.current!!) { ModAssistantViewModel() }
    CompositionLocalProvider(
        LocalMainPageNav provides navController,
        LocalModAssistantViewModel provides modAssistantViewModel
    ) {
        NavHost(
            navController = navController,
            startDestination = MainPageDestination.Home.route,
            modifier = modifier
        ) {
            composable(route = MainPageDestination.Home.route) {
                MainPage()
            }

            composable(route = MainPageDestination.Setting.route) {
                 SettingPage(
                     navigateToHome = {
                         navController.navigateSingleTopTo(MainPageDestination.Home.route)
                     }
                 )
            }
        }
    }
}
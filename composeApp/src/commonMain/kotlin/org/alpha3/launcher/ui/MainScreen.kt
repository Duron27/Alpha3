package org.alpha3.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.InternalCoroutinesApi
import org.alpha3.launcher.ui.controls.UIStateManager.transparentBlack
import org.alpha3.launcher.ui.navigation.MainPageDestination
import org.alpha3.launcher.ui.navigation.MainPageNav
import org.alpha3.launcher.ui.view.MyFloatingActionButton
import org.alpha3.launcher.ui.view.NavigationSuiteScaffold
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.getLayoutType
import org.alpha3.launcher.utils.navigateSingleTopTo
import org.alpha3.launcher.utils.stringRes

@OptIn(ExperimentalMaterial3Api::class, InternalCoroutinesApi::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStack?.destination
    val currentScreen = MainPageDestination.entries.find { it.route == currentDestination?.route } ?: MainPageDestination.Home
    val codeGroup by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    // With a rail the settings pages are tabs on the main bar, so the rail has nothing left but Play: float that instead.
    val floatingPlay = getLayoutType() != NavigationSuiteType.NavigationBar && codeGroup == "OpenMW"
    val layoutType = if (floatingPlay) NavigationSuiteType.None else getLayoutType()

    Box(modifier = Modifier.fillMaxSize()) {
    NavigationSuiteScaffold(
        layoutType = layoutType,
        containerColor = Color.Transparent,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = transparentBlack,
            navigationRailContainerColor = transparentBlack,
            navigationDrawerContainerColor = transparentBlack
        ),
        navigationSuiteItems = {
            MainPageDestination.entries.forEachIndexed { _, it ->
                item(
                    selected = it == currentScreen,
                    icon = {
                        if (it != MainPageDestination.StartGame) {
                            Icon(
                                imageVector = it.getIcon(it == currentScreen),
                                contentDescription = null
                            )
                        } else {
                            Box {
                                MyFloatingActionButton() // Start Game Button
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Start Game",
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(28.dp)
                                        .offset(x = 8.dp, y = 8.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                        }
                    },
                    onClick = {
                        if (it != MainPageDestination.StartGame) {
                            navController.navigateSingleTopTo(it.route)
                        }
                    },
                    label = if (it != MainPageDestination.StartGame) {
                        {
                            Text(text = stringRes(it.labelRes))
                        }
                    } else null
                )
            }
        }
    ) {
        MainPageNav(
            navController = navController,
        )
    }
    if (floatingPlay) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            MyFloatingActionButton()
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Start Game",
                modifier = Modifier.align(Alignment.BottomEnd).size(28.dp).offset(x = 8.dp, y = 8.dp),
                tint = Color(0xFF4CAF50)
            )
        }
    }
    }
}
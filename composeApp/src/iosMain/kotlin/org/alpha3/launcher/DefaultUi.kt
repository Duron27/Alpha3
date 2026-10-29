@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.alpha3.launcher

import org.alpha3.launcher.io.File
import org.alpha3.launcher.ui.controls.UIStateManager
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/**
 * Android copies its bundled `ui/` folder (default button layout and icons) into the game's UI directory on
 * first run (ManageAssets). Do the same here from the app bundle's OpenMWAssets/ui, which the build
 * mirrors from composeApp/UI (see syncIosUiAssets in composeApp/build.gradle.kts).
 */
fun installDefaultUi() {
    val dir = UIStateManager.userUI
    File(dir).mkdirs()
    val config = File("$dir/button_configs.json")
    if (!config.exists()) {
        val bundled = "${NSBundle.mainBundle.resourcePath}/OpenMWAssets/ui"
        val fm = NSFileManager.defaultManager
        val names = (fm.contentsOfDirectoryAtPath(bundled, null) as? List<*>).orEmpty().filterIsInstance<String>()
        if (names.isEmpty()) println("installDefaultUi: no bundled UI files in $bundled")
        names.forEach { name ->
            val target = "$dir/$name"
            if (!fm.fileExistsAtPath(target)) fm.copyItemAtPath("$bundled/$name", target, null)
        }
    }
    // The shipped layout points its icons at Android's storage; aim them at this device's UI folder.
    // Also repairs layouts installed by an earlier run.
    if (!config.exists()) return
    val text = config.readText()
    if (AndroidUiPrefix in text) config.writeText(text.replace(AndroidUiPrefix, "file://$dir/"))
}

private const val AndroidUiPrefix = "file:///storage/emulated/0/Alpha3/OpenMW/ui/"

package org.alpha3.launcher.ui.page.main

import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.IO
import org.alpha3.launcher.resources.an_error_occurred_while_selecting_the_folder
import org.alpha3.launcher.resources.cancel
import org.alpha3.launcher.resources.checking_data_paths
import org.alpha3.launcher.resources.config_file_missing
import org.alpha3.launcher.resources.error
import org.alpha3.launcher.resources.found_morrowind_esm_msg
import org.alpha3.launcher.resources.game_files_found
import org.alpha3.launcher.resources.game_files_location_set
import org.alpha3.launcher.resources.morrowind_esm_not_found
import org.alpha3.launcher.resources.morrowind_esm_not_found_msg
import org.alpha3.launcher.resources.morrowind_esm_not_in_cfg_msg
import org.alpha3.launcher.resources.no
import org.alpha3.launcher.resources.not_found
import org.alpha3.launcher.resources.ok
import org.alpha3.launcher.resources.openmw_config_file_not_found_msg
import org.alpha3.launcher.resources.please_select_a_folder_with_morrowind_ini_and_data_files
import org.alpha3.launcher.resources.searching_for_morrowind_esm
import org.alpha3.launcher.resources.selected_folder
import org.alpha3.launcher.resources.yes
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.utils.PlatformContext
import org.alpha3.launcher.utils.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.MorrowindSetup
import org.alpha3.launcher.ui.page.mod.ModUIState
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.view.ProgressDialog
import org.alpha3.launcher.ui.view.dismiss
import org.alpha3.launcher.ui.view.moeDialog
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.MToast
import org.alpha3.launcher.utils.stringRes
import org.alpha3.launcher.io.File

class MainPageViewModel() : ViewModel() {

    val showFileBrowser = mutableStateOf(false)
    val persistedPath = mutableStateOf<String?>(null)

    fun onGameFolderSelected(folder: File, context: PlatformContext) {
        if (File(folder, "Morrowind.ini").exists() && File(folder, "Data Files").isDirectory) {
            MToast("${stringRes(Res.string.selected_folder)}${folder.name}")
            applyGameFolder(context, folder)
            showFileBrowser.value = false
        } else {
            viewModelScope.launch(Dispatchers.IO) { GameFilesPreferences.setPreference(GameFilesPreferences.GAME_FILES_URI_KEY, "") }
            MToast(stringRes(Res.string.please_select_a_folder_with_morrowind_ini_and_data_files))
        }
    }

    private fun applyGameFolder(context: PlatformContext, folder: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = folder.absolutePath
                GameFilesPreferences.setPreference(GameFilesPreferences.GAME_FILES_URI_KEY, path)
                persistedPath.value = path
                Log.d("Add_Morrowind", "Add_Morrowind: $path")
                UIStateManager.useNavmesh = false

                // point OpenMW at the game, then import Morrowind.ini like the desktop launcher does
                val result = MorrowindSetup.setUp(path) { Log.d("Add_Morrowind", it) }
                check(result == 0) { "Morrowind.ini import failed ($result)" }
                ModUIState.refreshUI()
            } catch (e: Exception) {
                e.printStackTrace()
                persistedPath.value = null
                MToast(stringRes(Res.string.an_error_occurred_while_selecting_the_folder))
            }
        }
    }

    fun selectMorrowWindFolder(context: PlatformContext) {
        val progress = "".moeDialog(fullCustom = true) { ProgressDialog(text = stringRes(Res.string.searching_for_morrowind_esm)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userCfg = File(OpenMWPaths.USER_OPENMW_CFG)
                if (!userCfg.exists()) {
                    progress.dismiss()
                    showErrorAndBrowse(Res.string.config_file_missing, Res.string.openmw_config_file_not_found_msg)
                    return@launch
                }

                val lines = userCfg.readLines()
                val hasEsm = lines.any { it.trim().startsWith("content=Morrowind.esm") }
                val paths = lines.filter { it.trim().startsWith("data=") }
                    .map { it.removePrefix("data=\"").removeSuffix("\"").trim() }

                progress.dismiss()
                if (!hasEsm) {
                    showErrorAndBrowse(Res.string.morrowind_esm_not_found, Res.string.morrowind_esm_not_in_cfg_msg, true)
                    return@launch
                }

                val searchProgress = "".moeDialog(fullCustom = true) { ProgressDialog(text = stringRes(Res.string.checking_data_paths)) }
                val esmDir = findMorrowindEsm(paths)
                searchProgress.dismiss()

                esmDir?.let { dir ->
                    stringRes(Res.string.found_morrowind_esm_msg, dir).moeDialog(
                        title = stringRes(Res.string.game_files_found),
                        confirmLabel = stringRes(Res.string.yes),
                        onConfirm = {
                            val iniFile = listOf(File(dir, "Morrowind.ini"), dir.parentFile?.let { File(it, "Morrowind.ini") })
                                .firstOrNull { it?.exists() == true }
                            applyGameFolder(context, iniFile?.parentFile ?: dir)
                            MToast(stringRes(Res.string.game_files_location_set))
                        },
                        dismissLabel = stringRes(Res.string.no),
                        onDismiss = { showFileBrowser.value = true }
                    )
                } ?: showErrorAndBrowse(Res.string.not_found, Res.string.morrowind_esm_not_found_msg)
            } catch (e: Exception) {
                progress.dismiss()
                (stringRes(Res.string.error) + ": " + e.message).moeDialog(confirmLabel = stringRes(Res.string.ok))
            }
        }
    }

    private fun showErrorAndBrowse(titleRes: StringResource, msgRes: StringResource, cancellable: Boolean = false) {
        stringRes(msgRes).moeDialog(
            title = stringRes(titleRes),
            confirmLabel = stringRes(Res.string.ok),
            onConfirm = { showFileBrowser.value = true },
            dismissLabel = if (cancellable) stringRes(Res.string.cancel) else null
        )
    }

    private suspend fun findMorrowindEsm(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.firstNotNullOfOrNull { path ->
            File(path).takeIf { it.isDirectory }?.walk()?.maxDepth(3)?.firstOrNull {
                it.isFile && it.name.equals("Morrowind.esm", true)
            }?.parentFile
        }
    }
}

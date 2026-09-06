package org.openmw.ui.page.main

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmw.Constants
import org.openmw.R
import org.openmw.runIniImporter
import org.openmw.ui.controls.UIStateManager
import org.openmw.ui.view.ProgressDialog
import org.openmw.ui.view.dismiss
import org.openmw.ui.view.moeDialog
import org.openmw.utils.GameFilesPreferences
import org.openmw.utils.MToast
import org.openmw.utils.stringRes
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainPageViewModel @Inject constructor() : ViewModel() {

    val showFileBrowser = mutableStateOf(false)
    val persistedPath = mutableStateOf<String?>(null)

    fun onGameFolderSelected(folder: File, context: Context) {
        if (File(folder, "Morrowind.ini").exists() && File(folder, "Data Files").isDirectory) {
            MToast("${stringRes(R.string.selected_folder)}${folder.name}")
            applyGameFolder(context, folder)
            showFileBrowser.value = false
        } else {
            viewModelScope.launch(Dispatchers.IO) { GameFilesPreferences.setPreference(context, GameFilesPreferences.GAME_FILES_URI_KEY, "") }
            MToast(stringRes(R.string.please_select_a_folder_with_morrowind_ini_and_data_files))
        }
    }

    private fun applyGameFolder(context: Context, folder: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = folder.absolutePath
                GameFilesPreferences.setPreference(context, GameFilesPreferences.GAME_FILES_URI_KEY, path)
                persistedPath.value = path
                Log.d("Add_Morrowind", "Add_Morrowind: $path")
                UIStateManager.useNavmesh = false

                updateConfigs(path)
                runIniImporter(context, path)
            } catch (e: Exception) {
                e.printStackTrace()
                persistedPath.value = null
                MToast(stringRes(R.string.an_error_occurred_while_selecting_the_folder))
            }
        }
    }

    private fun updateConfigs(path: String) {
        val extensions = arrayOf("bsa", "esm", "esp", "esl", "omwaddon", "omwgame", "omwscripts")
        val dataLine = """data="$path/Data Files""""
        val vfsLine = "data=${Constants.USER_RESOURCES}/vfs-mw"
        val overrideLine = "data-local=${Constants.USER_FILE_STORAGE}/OpenMW/Override"
        val dataRegex = Regex("""^data\s*=\s*".*?"""")

        val dataFilesDir = File("$path/Data Files")
        val targetDir = if (dataFilesDir.exists() && dataFilesDir.isDirectory) dataFilesDir else File(path)

        val foundFiles = mutableListOf<File>()
        fun walkDir(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    walkDir(f)
                } else if (f.isFile) {
                    if (f.extension.lowercase() in extensions) {
                        foundFiles.add(f)
                    }
                }
            }
        }
        if (targetDir.exists() && targetDir.isDirectory) {
            walkDir(targetDir)
        }

        val pluginLinesToAdd = foundFiles.map { file ->
            val fileName = file.name
            if (file.extension.lowercase() == "bsa") {
                "fallback-archive=$fileName"
            } else {
                "content=$fileName"
            }
        }

        File(Constants.OPENMW_CFG).takeIf { it.exists() }?.let { file ->
            val lines = file.readLines().map { line ->
                when {
                    line.contains(dataRegex) -> dataLine
                    line.trim() == "resources=./resources" -> "resources=${Constants.USER_RESOURCES}"
                    line.trim().startsWith("data=") && line.contains("vfs-mw") -> vfsLine
                    line.trim().startsWith("data-local=") -> overrideLine
                    else -> line
                }
            }.toMutableList()
            if (lines.none { it.contains("vfs-mw") }) lines.add(vfsLine)
            if (lines.none { it.startsWith("data-local=") }) lines.add(overrideLine)

            file.writeText(lines.joinToString("\n"))
        }

        File(Constants.USER_OPENMW_CFG).takeIf { it.exists() }?.let { file ->
            val lines = file.readLines().toMutableList()
            if (lines.none { it.trim() == dataLine }) {
                val idx = lines.indexOfFirst { it.trim().startsWith("data=") }.let { if (it == -1) 0 else it }
                lines.add(idx, dataLine)
            }

            pluginLinesToAdd.forEach { pluginLine ->
                if (lines.none { it.trim().removePrefix(";") == pluginLine }) {
                    lines.add(pluginLine)
                }
            }

            file.writeText(lines.joinToString("\n"))
        }
    }

    fun selectMorrowWindFolder(context: Context) {
        val progress = "".moeDialog(fullCustom = true) { ProgressDialog(text = stringRes(R.string.searching_for_morrowind_esm)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userCfg = File(Constants.USER_OPENMW_CFG)
                if (!userCfg.exists()) {
                    progress.dismiss()
                    showErrorAndBrowse(R.string.config_file_missing, R.string.openmw_config_file_not_found_msg)
                    return@launch
                }

                val lines = userCfg.readLines()
                val hasEsm = lines.any { it.trim().startsWith("content=Morrowind.esm") }
                val paths = lines.filter { it.trim().startsWith("data=") }
                    .map { it.removePrefix("data=\"").removeSuffix("\"").trim() }

                progress.dismiss()
                if (!hasEsm) {
                    showErrorAndBrowse(R.string.morrowind_esm_not_found, R.string.morrowind_esm_not_in_cfg_msg, true)
                    return@launch
                }

                val searchProgress = "".moeDialog(fullCustom = true) { ProgressDialog(text = stringRes(R.string.checking_data_paths)) }
                val esmDir = findMorrowindEsm(paths)
                searchProgress.dismiss()

                esmDir?.let { dir ->
                    String.format(stringRes(R.string.found_morrowind_esm_msg), dir).moeDialog(
                        title = stringRes(R.string.game_files_found),
                        confirmLabel = stringRes(R.string.yes),
                        onConfirm = {
                            val iniFile = listOf(File(dir, "Morrowind.ini"), dir.parentFile?.let { File(it, "Morrowind.ini") })
                                .firstOrNull { it?.exists() == true }
                            applyGameFolder(context, iniFile?.parentFile ?: dir)
                            MToast(stringRes(R.string.game_files_location_set))
                        },
                        dismissLabel = stringRes(R.string.no),
                        onDismiss = { showFileBrowser.value = true }
                    )
                } ?: showErrorAndBrowse(R.string.not_found, R.string.morrowind_esm_not_found_msg)
            } catch (e: Exception) {
                progress.dismiss()
                (stringRes(R.string.error) + ": " + e.localizedMessage).moeDialog(confirmLabel = stringRes(R.string.ok))
            }
        }
    }

    private fun showErrorAndBrowse(titleRes: Int, msgRes: Int, cancellable: Boolean = false) {
        stringRes(msgRes).moeDialog(
            title = stringRes(titleRes),
            confirmLabel = stringRes(R.string.ok),
            onConfirm = { showFileBrowser.value = true },
            dismissLabel = if (cancellable) stringRes(R.string.cancel) else null
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

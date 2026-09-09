package org.openmw.utils

import android.util.Log
import org.openmw.ui.controls.UIStateManager
import org.openmw.OPENMW_MAIN_LIB
import org.openmw.UQM_MAIN_LIB
import org.openmw.DETHRACE_MAIN_LIB

object LibraryLoader {
    private const val TAG = "LibraryLoader"

    fun loadLibraries(): Array<String> {
        val base = arrayOf("SDL2", "GLESv3", "c++_shared", "openal")

        val jniLibsArray = when (UIStateManager.tempCodeGroup) {
            "OpenMW" -> base + "openmw"
            "UQM" -> base + "uqm"
            "Dethrace" -> base + "dethrace"
            else -> emptyArray()
        }

        jniLibsArray.forEach {
            try {
                System.loadLibrary(it)
                Log.d(TAG, "Successfully loaded library: $it")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load library: $it", e)
            }
        }
        return jniLibsArray
    }

    fun getMainSharedObject(): String {
        return when (UIStateManager.tempCodeGroup) {
            "OpenMW" ->  OPENMW_MAIN_LIB
            "UQM" -> UQM_MAIN_LIB
            "Dethrace" -> DETHRACE_MAIN_LIB
            else -> ""
        }
    }
}

package org.alpha3.launcher

import kotlinx.cinterop.ExperimentalForeignApi
import org.alpha3.launcher.tools.openmw.OkioToolFs
import org.alpha3.launcher.tools.openmw.OsKind
import org.alpha3.launcher.tools.openmw.ToolPlatform
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.dateWithTimeIntervalSince1970

/** okio storage for the OpenMW tools, plus the modification times tes3cmd restores on the plugins it rewrites. */
object IosToolFs : OkioToolFs() {
    @OptIn(ExperimentalForeignApi::class)
    override fun setLastModified(path: String, millis: Long) {
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)),
            ofItemAtPath = path,
            error = null,
        )
    }
}

@Suppress("UNCHECKED_CAST")
fun iosToolPlatform(): ToolPlatform =
    ToolPlatform(OsKind.Ios, NSHomeDirectory(), NSProcessInfo.processInfo.environment as Map<String, String>)

package org.openmw.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * The launcher's terminal: Android's shell (/system/bin/sh, mksh) in a pseudo-terminal, drawn by Termux's xterm
 * emulator (terminal-emulator / terminal-view). Colours, the cursor, full screen programs (top, vi, less), Ctrl-C and
 * the shell's own history and line editing all work as in any terminal. The session outlives the screen: leaving the
 * tab and coming back finds it as it was.
 *
 * Long press selects text (copy and paste in its toolbar), pinch changes the text size, the row of keys under it gives
 * what the touch keyboard lacks (Esc, Ctrl, Alt, Tab, the arrows, ...).
 */
@Composable
fun Terminal() {
    val context = LocalContext.current
    var fontSize by remember { mutableIntStateOf(ShellSession.fontSize) }
    var showHelp by remember { mutableStateOf(false) }
    val state = ShellSession.state

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black)
    ) {
        TerminalHeader(
            title = state.title,
            finished = state.finished,
            onCopy = {
                val text = ShellSession.transcript()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
                Toast.makeText(context, "Copied the terminal's text", Toast.LENGTH_SHORT).show()
            },
            onPaste = { ShellSession.paste(context) },
            onSmaller = { fontSize = ShellSession.setFontSize(fontSize - 2) },
            onLarger = { fontSize = ShellSession.setFontSize(fontSize + 2) },
            onKeyboard = { ShellSession.showKeyboard() },
            onRestart = { ShellSession.restart(context) },
            onHelp = { showHelp = true },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                factory = { ctx -> ShellSession.makeView(ctx) },
                update = { view -> view.setTextSize(ShellSession.fontPixels(view.context, fontSize)) },
            )
            if (state.finished) {
                Text(
                    "[the shell has exited: tap ⟲ to start a new one]",
                    color = Color(0xFFFFB000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black).padding(6.dp),
                )
            }
        }

        ExtraKeys()
    }

    DisposableEffect(Unit) {
        onDispose { ShellSession.detachView() }
    }

    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
}

/** What the header shows, updated from the session's callbacks. */
class ShellState {
    var title by mutableStateOf("")
    var finished by mutableStateOf(false)
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
}

/**
 * The one shell session and the view drawing it. The session lives as long as the app process (or until the shell
 * exits); the view is made again each time the terminal is shown.
 */
object ShellSession {
    private const val TAG = "Terminal"
    val state = ShellState()
    var fontSize = 13
        private set

    private var session: TerminalSession? = null
    private var view = WeakReference<TerminalView>(null)
    private var kramStarted = false

    fun fontPixels(context: Context, sp: Int): Int = (sp * context.resources.displayMetrics.scaledDensity).toInt()

    fun setFontSize(sp: Int): Int {
        fontSize = sp.coerceIn(6, 40)
        view.get()?.let { it.setTextSize(fontPixels(it.context, fontSize)) }
        return fontSize
    }

    fun makeView(context: Context): TerminalView {
        val v = TerminalView(context, null)
        v.setTerminalViewClient(viewClient)
        v.setTextSize(fontPixels(context, fontSize))
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.keepScreenOn = false
        view = WeakReference(v)
        v.attachSession(obtain(context))
        // once it is in the window: before, the focus would stay with the launcher's tabs (and Enter would press one)
        v.post { v.requestFocus() }
        return v
    }

    fun detachView() {
        view = WeakReference(null)
    }

    fun showKeyboard() {
        val v = view.get() ?: return
        v.requestFocus()
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    fun transcript(): String = session?.emulator?.screen?.transcriptText?.trim() ?: ""

    fun paste(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    fun restart(context: Context) {
        session?.finishIfRunning()
        session = null
        state.finished = false
        view.get()?.attachSession(obtain(context))
    }

    /** A key of the row under the terminal: [keyCode] through the view (arrows follow the program's cursor mode). */
    fun sendKey(keyCode: Int) {
        val v = view.get() ?: return
        var meta = 0
        if (readCtrl()) meta = meta or KeyEvent.META_CTRL_ON
        if (readAlt()) meta = meta or KeyEvent.META_ALT_ON
        v.handleKeyCode(keyCode, meta)
    }

    fun sendText(text: String) {
        val s = session ?: return
        val ctrl = readCtrl()
        val alt = readAlt()
        text.codePoints().forEach { c ->
            // Ctrl with a letter: its control character, as the keyboard would make it
            val code = if (ctrl && c in 'a'.code..'z'.code) c - 'a'.code + 1 else if (ctrl && c in '@'.code..'_'.code) c - '@'.code else c
            s.writeCodePoint(alt, code)
        }
    }

    /** The sticky Ctrl and Alt of the key row: read once by the next key, then let go. */
    private fun readCtrl(): Boolean = state.ctrl.also { if (it) state.ctrl = false }
    private fun readAlt(): Boolean = state.alt.also { if (it) state.alt = false }

    private fun obtain(context: Context): TerminalSession {
        session?.let { if (it.isRunning || !state.finished) return it }
        val app = context.applicationContext
        val dir = File(app.filesDir, "terminal").apply { mkdirs() }
        val home = Environment.getExternalStorageDirectory().absolutePath
        if (org.openmw.BuildConfig.OPENMW_FORK && !kramStarted) {
            kramStarted = startKramBridge(dir)
        }
        val rc = File(dir, "mkshrc")
        rc.writeText(shellRc(app, dir, kramStarted))

        val libDir = app.applicationInfo.nativeLibraryDir
        val env = HashMap(System.getenv()).apply {
            put("PATH", "$libDir:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin")
            put("LD_LIBRARY_PATH", "$libDir:/system/lib64:/system/lib")
            put("HOME", dir.absolutePath)
            put("TMPDIR", app.cacheDir.absolutePath)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "en_US.UTF-8")
            put("ENV", rc.absolutePath)
            put("STORAGE", home)
        }.map { (k, v) -> "$k=$v" }.toTypedArray()

        val s = TerminalSession("/system/bin/sh", home, arrayOf("sh"), env, 5000, sessionClient)
        session = s
        state.finished = false
        state.title = ""
        return s
    }

    /** Read by mksh at start (ENV): res/raw/mkshrc, Android's own rc and then the prompt, aliases, kram. */
    private fun shellRc(context: Context, dir: File, kram: Boolean): String =
        context.resources.openRawResource(org.openmw.R.raw.mkshrc).bufferedReader().use { it.readText() }
            .replace("@KRAM@", if (kram) "1" else "0")
            .replace("@DIR@", dir.absolutePath)

    /** The app's end of kram's pipes: waits for a command line, runs kram, writes its output to the shell. */
    private fun startKramBridge(dir: File): Boolean {
        val input = File(dir, "kram.in")
        val output = File(dir, "kram.out")
        val rc = File(dir, "kram.rc")
        return try {
            for (f in listOf(input, output)) {
                f.delete()
                Os.mkfifo(f.absolutePath, "600".toInt(8))
            }
            thread(name = "kram-bridge", isDaemon = true) {
                while (true) {
                    try {
                        val bytes = FileInputStream(input).use { it.readBytes() }
                        val args = String(bytes).split('\u0000').dropLast(1)
                        FileOutputStream(output).bufferedWriter().use { out ->
                            val code = try {
                                runBlocking {
                                    AndroidKramRunner().run(args) { line ->
                                        out.write(line)
                                        out.write("\n")
                                        out.flush()
                                    }
                                }
                            } catch (e: Exception) {
                                out.write("kram: ${e.message}\n")
                                1
                            }
                            rc.writeText(code.toString())
                        }
                    } catch (e: Exception) {
                        // the shell stopped reading (Ctrl-C): wait for the next command
                        Log.w(TAG, "kram bridge: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "kram bridge not started: ${e.message}")
            false
        }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            view.get()?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            state.title = changedSession.title ?: ""
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            state.finished = true
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val v = view.get() ?: return
            val clipboard = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            view.get()?.let { paste(it.context) }
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            view.get()?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(TAG, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "", e) }
    }

    private val viewClient = object : TerminalViewClient {
        private var scale = 1f

        override fun onScale(scale: Float): Float {
            // pinch: a step of text size once the fingers have moved far enough
            if (scale < 0.9f || scale > 1.1f) {
                setFontSize(fontSize + if (scale > 1f) 1 else -1)
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = readCtrl()
        override fun readAltKey() = readAlt()
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(TAG, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "", e) }
    }
}

@Composable
private fun TerminalHeader(
    title: String,
    finished: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
    onKeyboard: () -> Unit,
    onRestart: () -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (title.isBlank()) "OpenMW Shell" else title,
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
        if (finished) Text("EXITED ", color = Color(0xFFFFB000), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        HeaderIcon(Icons.Default.Keyboard, "Keyboard", onKeyboard)
        HeaderIcon(Icons.Default.ContentCopy, "Copy all", onCopy)
        HeaderIcon(Icons.Default.ContentPaste, "Paste", onPaste)
        HeaderIcon(Icons.Default.TextDecrease, "Smaller text", onSmaller)
        HeaderIcon(Icons.Default.TextIncrease, "Larger text", onLarger)
        HeaderIcon(Icons.Default.RestartAlt, "New shell", onRestart)
        HeaderIcon(Icons.AutoMirrored.Filled.HelpOutline, "Help", onHelp)
    }
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = label, tint = Color.LightGray, modifier = Modifier.size(18.dp))
    }
}

/** The keys a touch keyboard lacks. Ctrl and Alt stay down for the next key (lit while they do). */
@Composable
private fun ExtraKeys() {
    val state = ShellSession.state
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Key("ESC") { ShellSession.sendKey(KeyEvent.KEYCODE_ESCAPE) }
        Key("CTRL", lit = state.ctrl) { state.ctrl = !state.ctrl }
        Key("ALT", lit = state.alt) { state.alt = !state.alt }
        Key("TAB") { ShellSession.sendKey(KeyEvent.KEYCODE_TAB) }
        Key("^C") { ShellSession.sendText("\u0003") }
        Key("←") { ShellSession.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        Key("↓") { ShellSession.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        Key("↑") { ShellSession.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        Key("→") { ShellSession.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Key("HOME") { ShellSession.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        Key("END") { ShellSession.sendKey(KeyEvent.KEYCODE_MOVE_END) }
        Key("PGUP") { ShellSession.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        Key("PGDN") { ShellSession.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        for (c in listOf("-", "/", "|", "~", "*", ">", "&", ";")) Key(c) { ShellSession.sendText(c) }
    }
}

@Composable
private fun Key(label: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(min = 40.dp)
            .height(34.dp)
            .background(if (lit) Color(0xFF2E7D32) else Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val mono = FontFamily.Monospace
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal", color = Color(0xFF7CFC00), fontFamily = mono) },
        text = {
            Column {
                listOf(
                    "Android's shell (mksh) in a real terminal: colours,",
                    "full screen programs (top, vi, less), Ctrl-C.",
                    "",
                    "Tap: keyboard.  Long press: select, copy, paste.",
                    "Pinch: text size.  ↑/↓: the shell's history.",
                    "CTRL / ALT: held for the next key.",
                    "",
                    "storage  cd to the shared storage (~ in the prompt)",
                    "alpha3   cd to Alpha3's folder",
                    "ll, la   list files",
                    "kram     the texture tool (fork engine builds)",
                ).forEach { Text(it, color = Color.White, fontFamily = mono, fontSize = 12.sp) }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
        containerColor = Color(0xFF202020),
    )
}

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.alpha3.launcher

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.engine.KeyCodes
import org.alpha3.launcher.globals.PlayBridge
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIKey
import platform.UIKit.UIKeyInputProtocol
import platform.UIKit.UIPress
import platform.UIKit.UIPressesEvent
import platform.UIKit.UITextAutocapitalizationType
import platform.UIKit.UITextAutocorrectionType
import platform.UIKit.UITextSpellCheckingType
import platform.UIKit.UIView
import platform.UIKit.UIViewController

/**
 * The iOS system keyboard for the engine, used when the Compose virtual keyboard is disabled in settings.
 *
 * SDL's own UITextField based keyboard is not used (iOS 26 dismisses it immediately for this app). This is a
 * tiny invisible [UIKeyInputProtocol] view in the in-game window: while it is first responder the system
 * keyboard (or a hardware keyboard) feeds it, and what is typed is passed to SDL as text, Backspace and Return.
 */
private const val CTRL_FLAG = 1L shl 18
private const val COMMAND_FLAG = 1L shl 20

private class KeyInputView : UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)), UIKeyInputProtocol {
    override fun canBecomeFirstResponder() = true

    // Always report text so the keyboard keeps Backspace enabled on an empty field.
    override fun hasText() = true

    override fun insertText(text: String) {
        if (text == "\n") {
            Engine.onNativeKeyDown(KeyCodes.KEYCODE_ENTER)
            Engine.onNativeKeyUp(KeyCodes.KEYCODE_ENTER)
        } else {
            Engine.nativeCommitText(text, 0)
        }
    }

    // Hardware keyboard: delivered as presses, not through insertText. Send real key events (HID usage ==
    // SDL scancode) and, for printable keys, the text as well, the same as the Compose keyboard does.
    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!handlePresses(presses, down = true)) super.pressesBegan(presses, withEvent)
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!handlePresses(presses, down = false)) super.pressesEnded(presses, withEvent)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        if (!handlePresses(presses, down = false)) super.pressesCancelled(presses, withEvent)
    }

    private fun handlePresses(presses: Set<*>, down: Boolean): Boolean {
        val bridge = Engine.bridge as? IosEngineBridge ?: return false
        var handled = false
        presses.forEach { press ->
            val key = (press as? UIPress)?.key ?: return@forEach
            handled = true
            val scancode = key.keyCode.toInt()
            bridge.sendScancode(scancode, down)
            if (down && isPrintable(key, scancode)) Engine.nativeCommitText(key.characters, 0)
        }
        return handled
    }

    private fun isPrintable(key: UIKey, scancode: Int): Boolean {
        // Return, Escape, Backspace, Tab are keys, not text.
        if (scancode in 40..43) return false
        // Shortcuts (ctrl/cmd) are not text.
        if (key.modifierFlags and (CTRL_FLAG or COMMAND_FLAG) != 0L) return false
        val c = key.characters.singleOrNull() ?: return false
        // Arrows, function keys etc. report private-use characters.
        return c >= ' ' && c.code !in 0xF700..0xF8FF && c.code != 0x7F
    }

    override fun deleteBackward() {
        Engine.onNativeKeyDown(KeyCodes.KEYCODE_DEL)
        Engine.onNativeKeyUp(KeyCodes.KEYCODE_DEL)
    }

    // Typed text goes straight to the game: no autocorrect, capitalization or spell check.
    override fun autocorrectionType(): UITextAutocorrectionType = UITextAutocorrectionType.UITextAutocorrectionTypeNo
    override fun setAutocorrectionType(autocorrectionType: UITextAutocorrectionType) {}
    override fun autocapitalizationType(): UITextAutocapitalizationType = UITextAutocapitalizationType.UITextAutocapitalizationTypeNone
    override fun setAutocapitalizationType(autocapitalizationType: UITextAutocapitalizationType) {}
    override fun spellCheckingType(): UITextSpellCheckingType = UITextSpellCheckingType.UITextSpellCheckingTypeNo
    override fun setSpellCheckingType(spellCheckingType: UITextSpellCheckingType) {}
}

object SystemKeyboard {
    /** The in-game view controller whose view hosts the input view; set by [GameplayViewController]. */
    var host: UIViewController? = null

    private var input: KeyInputView? = null

    fun toggle() {
        val current = input
        if (current != null && current.isFirstResponder()) {
            current.resignFirstResponder()
            return
        }
        val hostView = host?.view ?: return
        val view = current ?: KeyInputView().also {
            hostView.addSubview(it)
            input = it
        }
        // Keyboard input goes to the key window, and SDL's window takes that role when it is created.
        hostView.window?.makeKeyWindow()
        val shown = view.becomeFirstResponder()
        PlayBridge.addLog("[input] system keyboard becomeFirstResponder=$shown")
    }
}

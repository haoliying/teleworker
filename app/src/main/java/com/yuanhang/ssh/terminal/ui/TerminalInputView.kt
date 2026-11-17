package com.yuanhang.ssh.terminal.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The keyboard sink for the terminal.
 *
 * A terminal has no text of its own – every keystroke is a byte that must reach the server
 * immediately. Driving that through a `TextField` means fighting the IME: the field's
 * contents have to be reset after each character, which restarts the input connection and
 * makes the keyboard flicker, and a reset issued while an IME is composing (pinyin, kana)
 * aborts the composition so the characters never arrive at all.
 *
 * This view instead hands the IME a purpose-built [InputConnection] that forwards
 * `commitText` / `deleteSurroundingText` straight to the shell and keeps no buffer of its
 * own, so nothing is ever reset and nothing is ever aborted.
 */
class TerminalInputView(context: Context) : View(context) {

    /** Text the IME has finished composing – send it as-is. */
    var onCommitText: ((String) -> Unit)? = null

    /** Backspace, `count` times. */
    var onDelete: ((count: Int) -> Unit)? = null

    /** A control sequence (Enter, arrows, Tab, function keys…). */
    var onSequence: ((String) -> Unit)? = null

    /** True while a hardware Ctrl / Alt is held, used to fold modifiers into the byte sent. */
    var modifierState: () -> Pair<Boolean, Boolean> = { false to false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection {
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        editorInfo.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_ACTION_NONE
        editorInfo.initialSelStart = 0
        editorInfo.initialSelEnd = 0
        return TerminalInputConnection(this)
    }

    /** Physical keyboards reach the view through the normal key dispatch path. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val e = event ?: return super.onKeyDown(keyCode, event)
        if (e.repeatCount > 0 && keyCode != KeyEvent.KEYCODE_DEL) return true

        val (ctrl, alt) = modifierState()
        val sequence = sequenceForKey(keyCode)
        if (sequence != null) {
            onSequence?.invoke(sequence)
            return true
        }

        // Ctrl/Alt must not be folded into keys that already generate a sequence.
        val unicode = e.getUnicodeChar(e.metaState and KeyEvent.META_SHIFT_MASK.inv())
        if (unicode == 0) return super.onKeyDown(keyCode, e)

        val ch = unicode.toChar().toString()
        onCommitText?.invoke(applyModifiers(ch, ctrl, alt))
        return true
    }

    companion object {

        /** DEL – what a shell expects when Backspace is pressed. */
        const val KEY_DELETE = "\u007F"

        fun sequenceForKey(keyCode: Int): String? = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> KEY_DELETE
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
            KeyEvent.KEYCODE_INSERT -> "\u001B[2~"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
            KeyEvent.KEYCODE_F1 -> "\u001BOP"
            KeyEvent.KEYCODE_F2 -> "\u001BOQ"
            KeyEvent.KEYCODE_F3 -> "\u001BOR"
            KeyEvent.KEYCODE_F4 -> "\u001BOS"
            KeyEvent.KEYCODE_F5 -> "\u001B[15~"
            KeyEvent.KEYCODE_F6 -> "\u001B[17~"
            KeyEvent.KEYCODE_F7 -> "\u001B[18~"
            KeyEvent.KEYCODE_F8 -> "\u001B[19~"
            KeyEvent.KEYCODE_F9 -> "\u001B[20~"
            KeyEvent.KEYCODE_F10 -> "\u001B[21~"
            KeyEvent.KEYCODE_F11 -> "\u001B[23~"
            KeyEvent.KEYCODE_F12 -> "\u001B[24~"
            else -> null
        }

        /**
         * Fold Ctrl/Alt into a character the way a terminal expects: Ctrl+A is byte 0x01,
         * Alt+X is ESC followed by X.
         */
        fun applyModifiers(text: String, ctrl: Boolean, alt: Boolean): String {
            // The soft keyboard's Enter arrives as LF; shells want CR.
            val normalized = text.replace('\n', '\r')
            if (!ctrl && !alt) return normalized
            return buildString {
                for (ch in normalized) {
                    when {
                        ctrl -> {
                            val upper = ch.uppercaseChar()
                            append(
                                when {
                                    upper in 'A'..'Z' -> ((upper.code - 'A'.code) + 1).toChar().toString()
                                    ch == ' ' -> "\u0000"
                                    ch == '[' -> "\u001B"
                                    ch == '\\' -> "\u001C"
                                    ch == ']' -> "\u001D"
                                    ch == '^' -> "\u001E"
                                    ch == '_' -> "\u001F"
                                    else -> ch
                                }
                            )
                        }
                        alt -> {
                            append('\u001B')
                            append(ch)
                        }
                        else -> append(ch)
                    }
                }
            }
        }
    }
}

/**
 * Forwards IME callbacks to the terminal and keeps no text of its own.
 *
 * Returning `true` from every mutation means the base class never touches the (empty)
 * editable, so the IME has no state to reconcile – which is exactly why the keyboard
 * stays put instead of restarting on every keystroke.
 */
private class TerminalInputConnection(private val host: TerminalInputView) :
    BaseInputConnection(host, false) {

    private val empty = SpannableStringBuilder("")

    override fun getEditable(): Editable = empty

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString() ?: return true
        if (value.isNotEmpty()) {
            val (ctrl, alt) = host.modifierState()
            host.onCommitText?.invoke(TerminalInputView.applyModifiers(value, ctrl, alt))
        }
        return true
    }

    /** Mid-composition (pinyin, kana…): hold it, it arrives again as [commitText]. */
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

    override fun finishComposingText(): Boolean = true

    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true

    override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val n = beforeLength.coerceAtLeast(0)
        if (n > 0) host.onDelete?.invoke(n)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val n = beforeLength.coerceAtLeast(0)
        if (n > 0) host.onDelete?.invoke(n)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return true
        if (e.action != KeyEvent.ACTION_DOWN) return true

        val sequence = TerminalInputView.sequenceForKey(e.keyCode)
        if (sequence != null) {
            host.onSequence?.invoke(sequence)
            return true
        }
        val unicode = e.getUnicodeChar(e.metaState and KeyEvent.META_SHIFT_MASK.inv())
        if (unicode != 0) {
            val (ctrl, alt) = host.modifierState()
            host.onCommitText?.invoke(
                TerminalInputView.applyModifiers(unicode.toChar().toString(), ctrl, alt)
            )
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        host.onSequence?.invoke("\r")
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean = true

    override fun setComposingRegion(start: Int, end: Int): Boolean = true
}

/**
 * Places a [TerminalInputView] in the composition and keeps its callbacks current.
 *
 * The view is deliberately 1 dp: it exists only to hold focus for the keyboard.
 */
@Composable
fun TerminalInputSink(
    focusRequester: FocusRequester,
    onCommitText: (String) -> Unit,
    onDelete: (Int) -> Unit,
    onSequence: (String) -> Unit,
    modifierState: () -> Pair<Boolean, Boolean> = { false to false },
    onReady: (TerminalInputView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // AndroidView captures the factory once; `update` refreshes the lambdas on every
    // recomposition so the view never calls into a stale session.
    AndroidView(
        factory = { ctx ->
            TerminalInputView(ctx).also { view ->
                view.onCommitText = { onCommitText(it) }
                view.onDelete = { onDelete(it) }
                view.onSequence = { onSequence(it) }
                view.modifierState = modifierState
                // Deferred: handing the view back during composition would mutate state
                // while the composition is still running.
                view.post { onReady(view) }
            }
        },
        update = { view ->
            view.onCommitText = { onCommitText(it) }
            view.onDelete = { onDelete(it) }
            view.onSequence = { onSequence(it) }
            view.modifierState = modifierState
        },
        modifier = modifier.focusRequester(focusRequester)
    )
}

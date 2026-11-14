package com.buccaneer.browser

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject

enum class TextMode { NONE, PROMPT, PAGE }

interface EditorStateView {
    val inputFrame: String
    val textMode: TextMode
    val editorSession: Long
}

/** Narrow UI adapter. No media owner, video activity, transport or renderer state is available. */
interface EditorHost {
    val context: Context
    val handler: Handler
    val webView: WebView
    val keypad: Keypad
    val lastTapAt: Long
    val lastUserInputAt: Long
    val editorBlocked: Boolean
    val panelsOrStartShown: Boolean
    fun pageAvailable(): Boolean
    fun eval(script: String, done: ((String?) -> Unit)? = null)
    fun runOnUiThread(action: () -> Unit)
    fun beginEditorInteraction()
    fun advanceInteraction()
    fun hideCursor()
    fun hideIme()
    fun updateEditorViewport()
    fun navigateInput(text: String)
    fun restoreEditorFocus()
    fun toastOnce(text: String)
}

/** Owns editing identity, preview, queue, cancellation and keypad lifetime as one subsystem. */
class EditorController(private val host: EditorHost, private val document: DocumentIdentity) : EditorHost by host, EditorStateView {
    private var acceptingCommands = false
    private var interactionSerial=0L
    fun interactionChanged() { interactionSerial++ }
    fun setForeground(active: Boolean) { acceptingCommands = active }
    /** Lifecycle invalidation cancels edits without touching media or changing the selected field. */
    fun invalidate() { editorSession++;cancelPageEdits() }
    fun resetPermit() { inputFocusConfirmed=true }
    fun clearField() { inputFrame="";inputFieldState="";inputFieldId="" }
    fun stopSync() { handler.removeCallbacks(syncRunnable) }
    fun abandonPrompt() { pendingJsPrompt=null } // Dead renderer: never call its result.
    fun dismissForPanels() {
        promptAllowsEmpty=false
        if (!keypad.shown()) return
        invalidate();clearField();stopSync();keypad.close()
        textMode=TextMode.NONE;promptDone=null;promptCancel=null
    }
    fun showJsPrompt(message: String?, initial: String?, result: android.webkit.JsPromptResult): Boolean {
        if (!pageAvailable() || !acceptingCommands) { result.cancel();return true }
        promptText((message ?: "Enter text").take(160), initial ?: "", {
            if(pendingJsPrompt === result) cancelPendingJsPrompt()
        }, "OK") {
            if(pendingJsPrompt === result) { pendingJsPrompt=null;result.confirm(it) }
        }
        pendingJsPrompt=result;promptAllowsEmpty=true;hideIme();return true
    }

    @Volatile override var inputFrame = ""; private set
    private var inputFieldState = ""
    private var inputFieldId = ""
    private var inputFocusConfirmed = true
    data class PageEdit(val action: String, val text: String, val session: Long,
        val token: String, val frame: String, val field: String)
    private val pageEdits = java.util.ArrayDeque<PageEdit>()
    private var pageEditActive: PageEdit? = null
    private var nativeEditCancel: (() -> Unit)? = null
    private var keyboardDismissedUntil = 0L
    override var editorSession = 0L; private set
    override var textMode = TextMode.NONE; private set
    private val buffer = StringBuilder()
    private var bufferPassword = false
    private var pendingJsPrompt: android.webkit.JsPromptResult? = null
    private var promptAllowsEmpty = false
    private var promptDone: ((String) -> Unit)? = null
    private var promptCancel: (() -> Unit)? = null
    private val syncRunnable = Runnable { syncField() }

    /** Base tap-recency window: a field focus within this of a real tap always registers. */
    private val TAP_RECENCY_BASE_MS = 2000L
    /** Extended band: a focus 2-5s after a tap (late-mounted search panels, e.g. some
     *  streaming sites' icon -> panel -> input) registers only when the field is visible — a hidden
     *  programmatic focus stays stray and never opens the keypad. */
    private val TAP_RECENCY_EXTENDED_MS = 5000L
    private fun tapRecencyOk(tapAge: Long, visible: Boolean): Boolean =
        tapAge in 0L..TAP_RECENCY_BASE_MS || (visible && tapAge in (TAP_RECENCY_BASE_MS + 1)..TAP_RECENCY_EXTENDED_MS)

    fun focusFromPage(state: String?) {
        hideIme()
        if (!pageAvailable() || !acceptingCommands || SystemClock.elapsedRealtime() < keyboardDismissedUntil) return
        // Media/D-pad activity and activity resume are not a request to type. A real
        // recent tap is required: base window 2s; the 2-5s band (late-mounted panels)
        // requires the field to be visible.
        val tapAge = SystemClock.uptimeMillis() - lastTapAt
        if (lastTapAt <= 0L || state == null) return
        val visible = try { JSONObject(state).optInt("vis", 0) == 1 } catch (_: Exception) { false }
        if (!tapRecencyOk(tapAge, visible)) return
        try { if (JSONObject(state).optInt("ok", 0) != 1) return } catch (_: Exception) { return }
        // Discovery notifications never overwrite a pinned editor's qualified state.
        if (textMode != TextMode.NONE || editorBlocked) return
        if (inputFrame.isEmpty()) inputFieldState = state
        openPageKeypad()
    }
    // Keyboard input (the on-screen keypad)

    fun cancelPendingJsPrompt(): Boolean {
        val result = pendingJsPrompt ?: return false
        pendingJsPrompt = null
        try { result.cancel() } catch (_: Exception) { }
        return true
    }

    fun promptText(
        title: String,
        initial: String,
        onCancel: (() -> Unit)?,
        goLabel: String = "OK",
        onDone: (String) -> Unit
    ) {
        beginEditorInteraction()
        editorSession++
        cancelPageEdits()
        cancelPendingJsPrompt()
        hideCursor()
        promptAllowsEmpty = false
        textMode = TextMode.PROMPT
        promptDone = onDone
        promptCancel = onCancel
        buffer.setLength(0)
        buffer.append(initial)
        keypad.open(title, goLabel)
        keypad.bringToFront()
        keypad.setPreview(buffer.toString())
        updateEditorViewport()
    }

    fun promptUrl() {
        promptText("Enter a web address or search", "", null, "GO") { text ->
            navigateInput(text)
        }
    }

    fun openPageKeypad() {
        beginEditorInteraction()
        editorSession++
        cancelPageEdits()
        cancelPendingJsPrompt()
        hideCursor()
        hideIme()
        promptAllowsEmpty = false
        textMode = TextMode.PAGE
        inputFocusConfirmed = true
        promptDone = null
        promptCancel = null
        buffer.setLength(0)
        bufferPassword = false
        keypad.open("Type into the web page", "ENTER")
        keypad.bringToFront()
        updateEditorViewport()
        keypad.post { updateEditorViewport() }
        webView.requestFocus()
        eval("window.__fb && __fb.focusIntoView()")
        syncFieldSoon()
    }

    /** Long-press Select: type into the page if a text field is active, otherwise ask for an address. */
    fun openKeypadSmart() {
        val interaction = interactionSerial
        val editor = editorSession
        eval("window.__fb ? __fb.field() : ''") { raw ->
            if (!acceptingCommands || interaction != interactionSerial || editor != editorSession || textMode != TextMode.NONE || panelsOrStartShown) return@eval
            var hasField = false
            try {
                val text = jsString(raw)
                if (!text.isNullOrEmpty()) hasField = JSONObject(text).optInt("ok", 0) == 1
            } catch (e: Exception) {
                hasField = false
            }
            if (hasField) openPageKeypad() else promptUrl()
        }
    }

    /** A real tap on a text field (reported by page_helpers.js) opens the keypad by itself. */
    fun onEditableClicked() {
        // The legacy helper has already checked the actual active editable after
        // a trusted click, including in a frame we cannot inspect from the top.
        focusFromPage("{\"ok\":1}")
    }

    private fun canTypeIntoPage(): Boolean {
        if (!pageAvailable() || !acceptingCommands) return false
        // A field recorded from a real tap is a typing permit: the JS edit path
        // targets it by ID without Android focus. Only an unselected field blocks.
        if (inputFrame.isEmpty() || inputFieldId.isEmpty()) {
            toastOnce("Select the search field again")
            return false
        }
        return true
    }

    private fun queuePageEdit(action: String, text: String = "") {
        if (!canTypeIntoPage() || textMode != TextMode.PAGE) return
        if (pageEdits.size >= 128 || text.length > 4096) {
            toastOnce("Typing queue is full; wait for the page")
            return
        }
        pageEdits.addLast(PageEdit(action, text, editorSession, document.token, inputFrame, inputFieldId))
        drainPageEdits()
    }

    private fun currentEdit(edit: PageEdit): Boolean = acceptingCommands && pageAvailable() &&
        textMode == TextMode.PAGE && edit.session == editorSession && edit.token == document.token &&
        edit.frame == inputFrame && edit.field == inputFieldId &&
        (inputFocusConfirmed || (edit.frame.isNotEmpty() && edit.field.isNotEmpty()))

    fun cancelPageEdits() {
        nativeEditCancel?.invoke()
        nativeEditCancel = null
        pageEdits.clear()
        pageEditActive = null
    }

    private fun drainPageEdits() {
        if (pageEditActive != null) return
        val edit = pageEdits.pollFirst() ?: return
        if (!currentEdit(edit)) { pageEdits.clear(); return }
        pageEditActive = edit
        val qualified = edit.frame.isNotEmpty() && edit.field.isNotEmpty()
        if (!qualified) {
            dispatchNativePageEdit(edit)
            return
        }
        val script = "window.__tvUi && __tvUi.edit ? __tvUi.edit(" +
            JSONObject.quote(edit.action) + "," + JSONObject.quote(edit.text) + "," +
            JSONObject.quote(edit.frame) + "," + JSONObject.quote(edit.field) + ",0) : '{\"status\":\"unreachable\"}'"
        eval(script) { raw ->
            if (pageEditActive !== edit) return@eval
            if (!currentEdit(edit)) { cancelPageEdits(); return@eval }
            val state = try { JSONObject(jsString(raw) ?: "") } catch (_: Exception) { null }
            val status = state?.optString("status", "").orEmpty()
            // Unreachable is a pre-action result, so native fallback cannot duplicate a DOM edit.
            // A timeout/exception/blocked edit is NOT permission to replay keystrokes.
            if (status == "unreachable") dispatchNativePageEdit(edit)
            else finishPageEdit(edit, status == "applied" || status == "submitted" || status == "key-dispatched", state)
        }
    }

    private fun dispatchNativePageEdit(edit: PageEdit) {
        nativeEditCancel = NativePageEditor.dispatch(webView, edit.action, edit.text) { accepted ->
            nativeEditCancel = null
            finishPageEdit(edit, accepted, null)
        }
    }

    private fun finishPageEdit(edit: PageEdit, accepted: Boolean, state: JSONObject?) {
        if (pageEditActive !== edit) return
        pageEditActive = null
        DiagnosticLog.record(context, "edit_result", JSONObject()
            .put("action", edit.action).put("field", edit.field)
            .put("status", state?.optString("status", "none") ?: "none"))
        if (!currentEdit(edit)) { pageEdits.clear(); return }
        if (!accepted) {
            buffer.setLength(0) // Never retain an unconfirmed edit as a claim of typed text.
            inputFocusConfirmed = false
            pageEdits.clear()
            keypad.setPreview("(website did not accept input)")
            toastOnce("Select the search field again; input was not confirmed")
            return
        }
        if (state?.optInt("ok", 0) == 1 && state.optString("doc") == edit.token &&
            state.optString("frame") == edit.frame && state.optString("field") == edit.field) {
            inputFieldState = state.toString()
            bufferPassword = state.optInt("pw", 0) == 1
            val preview = if (state.optInt("pw", 0) == 1) "•".repeat(state.optInt("length", 0).coerceIn(0, 8192)) else state.optString("v", "")
            keypad.setPreview(preview)
        }
        if (edit.action == "submit") { pageEdits.clear(); closeKeypad(); return }
        syncFieldSoon()
        drainPageEdits()
    }

    /** The preview shows what the user typed; the page's report refines it when available. */
    fun showBufferPreview() {
        keypad.setPreview(if (bufferPassword) "\u2022".repeat(buffer.length) else buffer.toString())
    }

    fun typeText(s: String) {
        if (textMode == TextMode.PAGE) {
            if (!canTypeIntoPage()) return
            buffer.append(s)
            showBufferPreview()
            queuePageEdit("insert", s); return
        }
        buffer.append(s)
        keypad.setPreview(buffer.toString())
    }

    fun typeBackspace() {
        if (textMode == TextMode.PAGE) {
            if (buffer.isNotEmpty()) buffer.setLength(buffer.offsetByCodePoints(buffer.length, -1))
            showBufferPreview()
            queuePageEdit("delete"); return
        }
        if (buffer.isNotEmpty()) buffer.setLength(buffer.offsetByCodePoints(buffer.length, -1))
        keypad.setPreview(buffer.toString())
    }

    fun typeClear() {
        if (textMode == TextMode.PAGE) {
            buffer.setLength(0)
            showBufferPreview()
            queuePageEdit("clear"); return
        }
        buffer.setLength(0)
        keypad.setPreview("")
    }

    fun typeGo() {
        if (textMode == TextMode.PAGE) { queuePageEdit("submit"); return }
        val allowEmpty = promptAllowsEmpty
        val text = if (allowEmpty) buffer.toString() else buffer.toString().trim()
        val done = promptDone
        val cancel = promptCancel
        closeKeypad()
        if (text.isEmpty() && !allowEmpty) cancel?.invoke() else done?.invoke(text)
    }

    fun cancelKeypad() {
        val cancel = if (textMode == TextMode.PROMPT) promptCancel else null
        closeKeypad()
        cancel?.invoke()
    }

    fun closeKeypad() {
        advanceInteraction()
        editorSession++
        cancelPageEdits()
        inputFrame = ""
        inputFieldState = ""
        inputFieldId = ""
        inputFocusConfirmed = true
        keyboardDismissedUntil = SystemClock.elapsedRealtime() + 1000L
        handler.removeCallbacks(syncRunnable)
        keypad.close()
        textMode = TextMode.NONE
        promptAllowsEmpty = false
        promptDone = null
        promptCancel = null
        updateEditorViewport()
        restoreEditorFocus()
    }

    fun syncFieldSoon() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, 90)
    }

    /** Mirror the page field's real contents (autocomplete, max length...) in the keypad preview. */
    fun syncField() {
        if (textMode != TextMode.PAGE) return
        val session = editorSession
        val frame = inputFrame
        val field = inputFieldId
        val token = document.token
        val qualified = frame.isNotEmpty() && field.isNotEmpty()
        val script = if (qualified) "window.__tvUi ? __tvUi.fieldState(" + JSONObject.quote(frame) + "," + JSONObject.quote(field) + ",0) : ''"
                     else "window.__fb ? __fb.field() : ''"
        eval(script) { raw ->
            if (acceptingCommands && session == editorSession && textMode == TextMode.PAGE) {
                var ok = false
                var value = ""
                var password = false
                if (qualified && (frame != inputFrame || field != inputFieldId || token != document.token)) return@eval
                val fresh = jsString(raw)
                val validFresh = if (qualified) try {
                    val o = JSONObject(fresh ?: "")
                    o.optInt("ok") == 1 && o.optString("doc") == token && o.optString("frame") == frame && o.optString("field") == field
                } catch (_: Exception) { false } else !fresh.isNullOrEmpty()
                // An unreachable cross-origin field may use its qualified bridge snapshot.
                val text = if (validFresh) fresh else if (qualified) inputFieldState else fresh
                if (!text.isNullOrEmpty()) {
                    try {
                        val o = JSONObject(text)
                        ok = o.optInt("ok", 0) == 1
                        value = o.optString("v", "")
                        password = o.optInt("pw", 0) == 1
                        if (password) value = "•".repeat(o.optInt("length", value.length).coerceIn(0, 8192))
                        bufferPassword = password
                    } catch (e: Exception) {
                        ok = false
                    }
                }
                if (ok) {
                    keypad.setPreview(if (password) "\u2022".repeat(value.length) else value)
                } else if (buffer.isEmpty()) {
                    keypad.setPreview("(tap a text field in the page first)")
                } else {
                    keypad.setPreview(buffer.toString())
                }
            }
        }
    }

    fun editableFocused(frame: String?, state: String?) {
        val id = frame?.take(120) ?: return
        val data = state?.takeIf { it.length <= 32768 && JsonInputBudget.allows(it) } ?: return
        val token = try { val o = JSONObject(data); if (o.optInt("ok", 0) != 1) return; o.optString("doc", "") } catch (_: Exception) { return }
        val epoch = document.epoch
        if (token != document.token) return
        runOnUiThread {
            if (epoch == document.epoch && token == document.token && pageAvailable() && acceptingCommands &&
                (textMode == TextMode.PAGE || SystemClock.elapsedRealtime() - lastUserInputAt <= 20_000L)) {
                val fieldId = try { JSONObject(data).optString("field", "") } catch (_: Exception) { "" }
                if (textMode == TextMode.PAGE && inputFrame.isNotEmpty() &&
                    (inputFrame != id || (inputFieldId.isNotEmpty() && inputFieldId != fieldId))) {
                    // Legacy/unqualified reports may be stale. Only the current
                    // focused-document snapshot may move an open editor's identity.
                    if (try { JSONObject(data).optInt("focused", 0) != 1 } catch (_: Exception) { true }) return@runOnUiThread
                    // Read-only identity reconciliation, never forced DOM focus.
                    editorSession++
                    cancelPageEdits()
                    buffer.setLength(0)
                    bufferPassword = false
                    handler.removeCallbacks(syncRunnable)
                }
                val tapAge = SystemClock.uptimeMillis() - lastTapAt
                val visible = try { JSONObject(data).optInt("vis", 0) == 1 } catch (_: Exception) { false }
                if (textMode == TextMode.PAGE && inputFrame.isEmpty() && !tapRecencyOk(tapAge, visible)) {
                    DiagnosticLog.record(context, "field_reg_drop", JSONObject()
                        .put("reason", "tap_window").put("sinceTapMs", tapAge.toString()).put("vis", if (visible) 1 else 0)
                        .put("field", fieldId).put("textMode", "PAGE"))
                    return@runOnUiThread
                }
                if (textMode != TextMode.PAGE && (lastTapAt <= 0 || !tapRecencyOk(tapAge, visible))) {
                    // Late-mounted search panels (some streaming sites) focus their input seconds after the
                    // tap; this drop was previously silent — a diagnostic blind spot.
                    DiagnosticLog.record(context, "field_reg_drop", JSONObject()
                        .put("reason", "tap_window").put("sinceTapMs", tapAge.toString()).put("vis", if (visible) 1 else 0)
                            .put("field", fieldId).put("textMode", textMode.name))
                    // Feedback instead of silence; only when a real tap preceded (programmatic
                    // focus on page load has no user intent to report back to).
                    if (lastTapAt > 0) toastOnce("Tap the field to type")
                    return@runOnUiThread
                }
                inputFrame = id
                inputFieldId = fieldId
                inputFieldState = data
                inputFocusConfirmed = true
                DiagnosticLog.record(context, "field_reg_ok", JSONObject()
                    .put("field", fieldId).put("frame", id)
                    .put("sinceTapMs", (SystemClock.uptimeMillis() - lastTapAt).toString()))
                focusFromPage(data)
                if (textMode == TextMode.PAGE) syncFieldSoon()
            }
        }
    }

    /** A blur invalidates the preview/typing permit until the focused field reports. */
    fun editableBlurred(frame: String?, token: String?, field: String?) {
        val epoch = document.epoch
        if (token != document.token) return
        runOnUiThread {
            if (epoch == document.epoch && token == document.token && acceptingCommands && pageAvailable() &&
                textMode == TextMode.PAGE && frame == inputFrame && field == inputFieldId) {
                // Search handlers commonly blur/remove their input on Enter.
                // The already-dispatched submit must not leave a dead keypad over results.
                if (pageEditActive?.action == "submit") {
                    closeKeypad()
                    return@runOnUiThread
                }
                // The recorded field identity stands: the JS edit path targets it by ID without
                // Android focus. Only a field the app never saw loses the typing permit.
                if (inputFieldId.isEmpty()) inputFocusConfirmed = false
                inputFieldState = ""
                editorSession++
                cancelPageEdits()
                handler.removeCallbacks(syncRunnable)
                showBufferPreview()
            }
        }
    }

}

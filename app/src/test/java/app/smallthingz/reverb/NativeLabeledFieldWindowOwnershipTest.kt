package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.text.Editable
import android.text.TextWatcher
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import smallthingz.ui.components.NativeLabeledTextField
import smallthingz.ui.components.NativeLabeledTextFieldStyle
import smallthingz.ui.components.NativeTextField
import smallthingz.ui.controls.ControlPalette

/** Actual host window/View lifecycle; no IME, device rendering or TalkBack claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLabeledFieldWindowOwnershipTest {
    @Test fun retainedFieldReacquiresValidationAcrossRealWindowDetachAndResetsWithoutEditing() {
        val owner = Robolectric.buildActivity(Activity::class.java)
        var primary: Throwable? = null
        try {
            owner.setup().visible()
            val activity = owner.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val palette = ControlPalette(-1, -1, -0x1000000, -0x777778,
                -0xffff01, -1, -0x333334, -0x777778)
            val editor = NativeTextField(activity, palette).apply {
                hint = "Native hint"
                setText("draft")
                setSelection(1, 4)
            }
            val editable = editor.editableText
            var edits = 0
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edits++ }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            val field = NativeLabeledTextField(activity, palette, "Recording name",
                NativeLabeledTextFieldStyle(errorColor = -0x10000, animationsEnabled = false), editor)
            field.setSupportingText("Choose a name")
            field.setError("Name unavailable")
            fun node(expectedHint: String, expectedError: String?) {
                val info = editor.createAccessibilityNodeInfo()
                try {
                    assertEquals(expectedHint, info.hintText?.toString())
                    assertEquals(expectedError, info.error?.toString())
                    assertEquals(expectedError != null, info.isContentInvalid)
                } finally { info.recycle() }
            }
            fun draftRetained() {
                assertSame(editable, editor.editableText)
                assertEquals("draft", editable.toString())
                assertEquals(1, editor.selectionStart)
                assertEquals(4, editor.selectionEnd)
                assertEquals(0, edits)
                assertNull(editor.error)
            }
            root.addView(field)
            assertTrue(field.isAttachedToWindow)
            node("Recording name", "Name unavailable")
            draftRetained()
            root.removeView(field)
            assertFalse(field.isAttachedToWindow)
            node("Native hint", null)
            draftRetained()
            root.addView(field)
            assertTrue(field.isAttachedToWindow)
            node("Recording name", "Name unavailable")
            draftRetained()
            field.setError(null)
            node("Recording name", null)
            assertEquals("Choose a name", field.supportingView.text.toString())
            root.removeView(field)
            node("Native hint", null)
            root.addView(field)
            node("Recording name", null)
            draftRetained()
            editable.append("!")
            assertEquals("draft!", editor.text.toString())
            assertEquals("The application listener remains installed exactly once", 1, edits)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            for (cleanup in listOf<() -> Unit>(
                { owner.pause(); Unit }, { owner.stop(); Unit }, { owner.destroy(); Unit },
            )) {
                try { cleanup() } catch (failure: Throwable) {
                    val previous = primary ?: cleanupFailure
                    if (previous == null) cleanupFailure = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }
}

package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.NativeTypography
import smallthingz.ui.text.NativeTypographyRole
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsRetentionValueAccessibilityTest {
    @Test
    fun sizeEditorsExposeTheirOwnLabelAndKeepNativeDecimalEditing() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(root)
        val environment = ReverbViewEnvironment(
            Density(1f), createFontFamilyResolver(activity), ReverbPalette.fallback(false),
        ).forSettings()
        val oneShot = SettingsRetentionInputView(activity, environment)
        val looping = SettingsRetentionInputView(activity, environment).apply { id = android.R.id.edit }
        val oneShotLabel = activity.getString(R.string.buffer_one_shot)
        val loopingLabel = activity.getString(R.string.retention_loop_label)
        val oneShotRow = SettingsRetentionValueView(activity, ReverbIcon.OneShot, oneShotLabel, oneShot, environment)
        val loopingRow = SettingsRetentionValueView(activity, ReverbIcon.Looping, loopingLabel, looping, environment)
        var oneShotRaw = "512"
        var loopingRaw = "512"
        try {
            root.addView(oneShotRow)
            root.addView(loopingRow)
            oneShot.bind(oneShotRaw, RetentionMode.SIZE, oneShotLabel, environment, false, { oneShotRaw }, { oneShotRaw = it })
            looping.bind(loopingRaw, RetentionMode.SIZE, loopingLabel, environment, false, { loopingRaw }, { loopingRaw = it })
            oneShotRow.bind(RetentionMode.SIZE, 512.0, 600, environment)
            loopingRow.bind(RetentionMode.SIZE, 512.0, 600, environment)
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 800)

            assertEquals(android.R.id.edit, looping.id)
            assertNotEquals(looping.id, oneShot.id)
            assertLabeledDecimalEditor(oneShotRow, oneShot, oneShotLabel)
            assertLabeledDecimalEditor(loopingRow, looping, loopingLabel)

            oneShot.editableText.replace(0, oneShot.length(), "384.5")
            assertEquals("384.5", oneShotRaw)
            assertEquals("512", loopingRaw)
            looping.editableText.replace(0, looping.length(), "256.25")
            assertEquals("384.5", oneShotRaw)
            assertEquals("256.25", loopingRaw)
        } finally {
            oneShot.releaseCallbacks()
            looping.releaseCallbacks()
            owner.pause().stop().destroy()
        }
    }

    @Test
    fun retainedEditorAndReadoutInheritWindowTypographyAndKeepExplicitValueMetrics() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val default = NativeUiAppearance.Default
        val role = NativeTypographyRole.BODY_LARGE
        val editorRecipe = default.editorVisuals.copy(largeValueText = default.editorVisuals.largeValueText.copy(fontSizeSp = 42f))
        val appearance = default.copy(editorVisuals = editorRecipe)
        val baseline = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(activity),
            ReverbPalette.fallback(false), appearance = appearance).forSettings()
        val changedTypography = NativeTypography(mapOf(role to default.typography[role].copy(fontFamily = FontFamily.Monospace)))
        val changedAppearance = appearance.copy(typography = changedTypography)
        val next = ReverbViewEnvironment(baseline.textDensity, baseline.sharedFontResolver, baseline.palette,
            appearance = changedAppearance).forSettings()
        val editor = SettingsRetentionInputView(activity, baseline)
        val row = SettingsRetentionValueView(activity, ReverbIcon.OneShot, "iii", editor, baseline)
        activity.setContentView(row)
        try {
            fun bind(environment: ReverbViewEnvironment) {
                editor.bind("512", RetentionMode.SIZE, "iii", environment, false, { "512" },
                    { error("A typography bind must not edit the value") })
                row.bind(RetentionMode.SIZE, 512.0, 600, environment)
                row.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            }
            bind(baseline)
            val heading = row.getChildAt(1) as ParagraphView
            val width = heading.measuredWidth
            val before = editor.paint.measureText("iii")

            bind(next)
            assertSame(editorRecipe, changedAppearance.editorVisuals)
            assertSame(heading, row.getChildAt(1))
            assertTrue(heading.measuredWidth > width)
            assertTrue(editor.paint.measureText("iii") > before)
            assertEquals(editor.paint.measureText("WWW"), editor.paint.measureText("iii"), .01f)
            assertEquals(42f, editor.textSize, .01f)
            assertLabeledDecimalEditor(row, editor, "iii")
        } finally {
            editor.releaseCallbacks()
            owner.pause().stop().destroy()
        }
    }

    private fun assertLabeledDecimalEditor(
        row: SettingsRetentionValueView,
        editor: SettingsRetentionInputView,
        label: String,
    ) {
        val heading = (0 until row.childCount).map(row::getChildAt).filterIsInstance<ParagraphView>()
            .single { it.getText() == label }
        val headingNode = requireNotNull(heading.createAccessibilityNodeInfo())
        val editorNode = requireNotNull(editor.createAccessibilityNodeInfo())
        try {
            assertEquals(label, headingNode.text.toString())
            // Platform node IDs let the attached View test inspect the relationship without an
            // accessibility service connection to resolve getLabeledBy() into a remote node.
            val undefinedId = ReflectionHelpers.getStaticField<Long>(AccessibilityNodeInfo::class.java, "UNDEFINED_NODE_ID")
            val headingId = ReflectionHelpers.getField<Long>(headingNode, "mSourceNodeId")
            val editorId = ReflectionHelpers.getField<Long>(editorNode, "mSourceNodeId")
            assertNotEquals("Heading must have a defined accessibility node ID", undefinedId, headingId)
            assertNotEquals("Editor must have a defined accessibility node ID", undefinedId, editorId)
            assertNotEquals("Heading and editor must be separate accessibility nodes", headingId, editorId)
            assertEquals("$label editor must refer to its visible heading", headingId,
                ReflectionHelpers.getField<Long>(editorNode, "mLabeledById"))
            assertEquals("$label heading must refer back to its editor", editorId,
                ReflectionHelpers.getField<Long>(headingNode, "mLabelForId"))
            assertEquals(EditText::class.java.name, editorNode.className.toString())
            assertEquals("512", editorNode.text.toString())
            assertTrue(editorNode.isEditable)
            assertTrue(editorNode.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
            val attrs = EditorInfo()
            requireNotNull(editor.onCreateInputConnection(attrs))
            assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, attrs.inputType)
            assertEquals(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN, attrs.imeOptions)
        } finally {
            headingNode.recycle()
            editorNode.recycle()
        }
    }
}

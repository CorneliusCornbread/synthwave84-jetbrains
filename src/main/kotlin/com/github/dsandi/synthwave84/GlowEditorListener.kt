package com.github.dsandi.synthwave84

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

class GlowEditorListener : EditorFactoryListener {

    // Neon colors sourced from the SynthWave '84 palette.
    // These sit above the regular text and glow outward.
    private val glowColors = mapOf(
        "keyword"    to Color(0xFF7EDB, false),   // pink
        "string"     to Color(0xFF8B39, false),   // orange
        "number"     to Color(0xF97E72, false),   // coral
        "function"   to Color(0x36F9F6, false),   // cyan
        "default"    to Color(0xFF7EDB, false)    // fallback pink
    )

    override fun editorCreated(event: EditorFactoryEvent) {
        applyGlowToEditor(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        // Highlighters are owned by the markup model and cleaned up automatically
    }

    private fun applyGlowToEditor(editor: Editor) {
        val document = editor.document
        if (document.textLength == 0) return

        val markupModel = editor.markupModel

        // High-layer highlighter that overrides text color to near-white
        // GUARDED_BLOCKS_ATTRIBUTES is one of the highest layers available
        val whiteAttrs = TextAttributes().apply {
            foregroundColor = Color(240, 240, 255)
        }
        val coreHighlighter = markupModel.addRangeHighlighter(
            0,
            document.textLength,
            HighlighterLayer.GUARDED_BLOCKS,
            whiteAttrs,
            HighlighterTargetArea.EXACT_RANGE
        )

        // Bloom highlighter below syntax for the colored glow
        val bloomHighlighter = markupModel.addRangeHighlighter(
            0,
            document.textLength,
            HighlighterLayer.SYNTAX - 1,
            null,
            HighlighterTargetArea.EXACT_RANGE
        )
        bloomHighlighter.customRenderer = GlowHighlighterRenderer()
    }

    companion object {
        fun applyToAllOpenEditors() {
            EditorFactory.getInstance().allEditors.forEach { editor ->
                GlowEditorListener().applyGlowToEditor(editor)
            }
        }
    }
}

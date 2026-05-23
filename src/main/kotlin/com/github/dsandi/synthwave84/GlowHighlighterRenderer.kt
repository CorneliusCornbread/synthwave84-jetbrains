package com.github.dsandi.synthwave84

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldingModel
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints

class GlowHighlighterRenderer(private val renderCore: Boolean = false) : CustomHighlighterRenderer {
    companion object {
        private const val PASSES = 6
        private const val SPREAD = 4.0f
        private const val BASE_ALPHA = 0.09f
        private const val MIN_BRIGHTNESS = 0.01f

        // Semantic colors from the SynthWave84 scheme
        val COLOR_FUNCTION_CALL = Color(54, 249, 246)    // cyan
        val COLOR_STRUCT_TRAIT  = Color(254, 68, 80)     // red
        val COLOR_TYPE_PARAM    = Color(32, 153, 157)    // teal
        val COLOR_FIELD         = Color(255, 126, 219)   // pink
        val COLOR_CONSTANT      = Color(249, 126, 114)   // coral
        val COLOR_MACRO         = Color(78, 173, 229)    // blue
        val COLOR_KEYWORD       = Color(254, 222, 93)    // yellow
        val COLOR_DEFAULT       = Color(240, 240, 255)   // near-white

        private val GLOW_TOKEN_TYPES = setOf(
            // Keywords
            "fn", "pub", "let", "mut", "use", "mod", "impl", "trait", "struct",
            "enum", "type", "const", "static", "where", "for", "in", "if", "else",
            "match", "return", "self", "Self", "super", "crate", "true", "false", "as",
            "async", "await", "move", "ref", "loop", "while", "break", "continue",
            "unsafe", "extern", "dyn", "box", "yield", "abstract", "become",
            "final", "macro", "override", "priv", "typeof", "unsized", "virtual",
            // Identifiers and types
            "identifier",
        )

        private val SKIP_TOKEN_TYPES = setOf(
            ";", ":", "::", ",", ".", "..", "...", "{", "}", "(", ")", "[", "]",
            "<", ">", "->", "=>", "=", "+", "-", "*", "/", "%", "&", "|", "^",
            "!", "~", "?", "@", "#", "\\", "WHITE_SPACE", "'", "\""
        )
    }

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        val g2d = g as? Graphics2D ?: return
        val document = editor.document
        val foldingModel = editor.foldingModel

        val visibleArea = editor.scrollingModel.visibleArea
        val firstVisibleLine = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line
        val lastVisibleLine = editor.xyToLogicalPosition(
            Point(0, visibleArea.y + visibleArea.height)
        ).line

        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val savedComposite = g2d.composite
        val savedColor = g2d.color
        val savedFont = g2d.font
        val savedHints = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING)

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.font = font

        for (line in firstVisibleLine..lastVisibleLine) {
            if (line >= document.lineCount) break

            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)

            if (foldingModel.getCollapsedRegionAtOffset(lineStart) != null) continue
            if (isCommentLine(editor, lineStart, lineEnd)) continue

            paintLineByTokens(editor, g2d, lineStart, lineEnd, foldingModel)
        }

        g2d.composite = savedComposite
        g2d.color = savedColor
        g2d.font = savedFont
        if (savedHints != null) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedHints)
        }
    }

    private fun paintLineByTokens(
        editor: Editor,
        g2d: Graphics2D,
        lineStart: Int,
        lineEnd: Int,
        foldingModel: FoldingModel
    ) {
        val document = editor.document
        val paintEnd = getVisibleLineEnd(foldingModel, lineStart, lineEnd)
        if (paintEnd <= lineStart) return

        val iterator = editor.highlighter.createIterator(lineStart)

        while (!iterator.atEnd() && iterator.start < paintEnd) {
            val tokenStart = maxOf(iterator.start, lineStart)
            val tokenEnd = minOf(iterator.end, paintEnd)

            if (tokenStart >= tokenEnd) {
                iterator.advance()
                continue
            }

            val tokenTypeName = iterator.tokenType?.toString() ?: ""

            // Only paint tokens in the glow allowlist
            if (SKIP_TOKEN_TYPES.contains(tokenTypeName) || !GLOW_TOKEN_TYPES.contains(tokenTypeName)) {
                iterator.advance()
                continue
            }

            // Skip dimmed tokens (unused vars, unnecessary qualifications etc.)
            if (isTokenDimmed(editor, tokenStart, tokenEnd)) {
                iterator.advance()
                continue
            }

            val tokenText = document.getText(TextRange(tokenStart, tokenEnd))
            if (tokenText.isBlank()) {
                iterator.advance()
                continue
            }

            // Resolve the correct semantic color for this token
            val glowColor = if (tokenTypeName == "identifier") {
                resolveIdentifierColor(editor, tokenStart, tokenEnd, tokenText)
            } else {
                iterator.textAttributes?.foregroundColor
                    ?: editor.colorsScheme.defaultForeground
            }

            val tokenPos = editor.offsetToXY(tokenStart)
            if (tokenPos != null) {
                val baselineY = tokenPos.y + editor.ascent
                val isDefaultColor = glowColor == COLOR_DEFAULT
                if (renderCore) {
                    paintCore(g2d, tokenText, tokenPos.x, baselineY, glowColor, isDefaultColor)
                } else {
                    paintBloom(g2d, tokenText, tokenPos.x, baselineY, glowColor)
                }
            }

            iterator.advance()
        }
    }

    private fun resolveIdentifierColor(
        editor: Editor,
        tokenStart: Int,
        tokenEnd: Int,
        tokenText: String
    ): Color {
        val document = editor.document
        val docLength = document.textLength

        // Look ahead past whitespace to find the next non-whitespace character
        var nextCharIdx = tokenEnd
        while (nextCharIdx < docLength && document.charsSequence[nextCharIdx] == ' ') {
            nextCharIdx++
        }
        val nextChar = if (nextCharIdx < docLength) document.charsSequence[nextCharIdx] else ' '

        // Look behind to find what precedes this token
        var prevCharIdx = tokenStart - 1
        while (prevCharIdx >= 0 && document.charsSequence[prevCharIdx] == ' ') {
            prevCharIdx--
        }
        val prevChar = if (prevCharIdx >= 0) document.charsSequence[prevCharIdx] else ' '

        // Check the token type of the previous meaningful token
        val prevTokenType = if (prevCharIdx >= 0) {
            editor.highlighter.createIterator(prevCharIdx).tokenType?.toString() ?: ""
        } else ""

        return when {
            // Macro calls: identifier followed by !
            nextChar == '!' -> COLOR_MACRO

            // Function calls: identifier followed by ( or :: then
            nextChar == '(' -> COLOR_FUNCTION_CALL
            nextChar == ':' && nextCharIdx + 1 < docLength
                    && document.charsSequence[nextCharIdx + 1] == ':' -> COLOR_FUNCTION_CALL

            // Struct/trait names: after `struct`, `trait`, `impl`, `for`, `enum`
            prevTokenType in setOf("struct", "trait", "enum") -> COLOR_STRUCT_TRAIT
            prevTokenType == "impl" -> COLOR_STRUCT_TRAIT
            prevTokenType == "for" -> COLOR_STRUCT_TRAIT

            // Type parameters: uppercase first letter and after < or ,
            (prevChar == '<' || prevChar == ',')
                    && tokenText.first().isUpperCase() -> COLOR_TYPE_PARAM

            // Constants: all uppercase with underscores
            tokenText == tokenText.uppercase()
                    && tokenText.length > 1
                    && tokenText.contains('_') -> COLOR_CONSTANT

            // Types/structs by convention: starts with uppercase
            tokenText.first().isUpperCase() -> COLOR_STRUCT_TRAIT

            // Fields: after `.`
            prevChar == '.' -> COLOR_FIELD

            // Everything else is near-white default
            else -> COLOR_DEFAULT
        }
    }

    private fun paintBloom(g2d: Graphics2D, text: String, x: Int, baselineY: Int, color: Color) {
        for (pass in 1..PASSES) {
            val fraction = pass.toFloat() / PASSES
            val alpha = BASE_ALPHA * (1f - fraction * 0.6f)
            val offset = fraction * SPREAD

            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g2d.color = color

            val offsets = listOf(
                Pair(-offset, -offset), Pair(offset, -offset),
                Pair(-offset, offset),  Pair(offset, offset),
                Pair(-offset, 0f),      Pair(offset, 0f),
                Pair(0f, -offset),      Pair(0f, offset)
            )
            for ((dx, dy) in offsets) {
                g2d.drawString(text, x + dx.toInt(), baselineY + dy.toInt())
            }
        }
    }

    private fun paintCore(
        g2d: Graphics2D,
        text: String,
        x: Int,
        baselineY: Int,
        color: Color,
        isDefaultColor: Boolean
    ) {
        // Paint on top of the editor's own text rendering to override
        // the theme's pink default foreground with near-white
        val coreColor = if (isDefaultColor) {
            Color(240, 240, 255) // near-white for identifiers
        } else {
            blendTowardWhite(color, 0.6f) // tinted white for keywords
        }
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f)
        g2d.color = coreColor
        g2d.drawString(text, x, baselineY)
    }

    private fun blendTowardWhite(color: Color, amount: Float): Color {
        val r = (color.red + ((255 - color.red) * amount)).toInt().coerceIn(0, 255)
        val g = (color.green + ((255 - color.green) * amount)).toInt().coerceIn(0, 255)
        val b = (color.blue + ((255 - color.blue) * amount)).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }

    private fun getTokenAttributes(editor: Editor, start: Int, end: Int): TextAttributes? {
        // Use the highlighter iterator directly — it already has the correct
        // TextAttributes baked in from the syntax highlighter + color scheme
        val iterator = editor.highlighter.createIterator(start)

        // Get base syntax color from the iterator's current token
        val syntaxAttrs = iterator.textAttributes?.clone()

        // Now layer on any markup model attributes (unused, error, warning etc.)
        // The markup model highlighters sit on top of syntax and can dim/override colors
        var hasDimmingEffect = false
        val markupHighlighters = editor.markupModel.allHighlighters
        for (h in markupHighlighters) {
            if (h.startOffset > start || h.endOffset < end) continue
            val attrs = h.getTextAttributes(editor.colorsScheme) ?: continue

            // Detect dimming effects used for unused/unnecessary code
            if (attrs.foregroundColor != null && isDimmed(attrs.foregroundColor!!, attrs)) {
                hasDimmingEffect = true
                break
            }
            if (attrs.effectType == EffectType.STRIKEOUT) {
                hasDimmingEffect = true
                break
            }
            // Some themes use a low-alpha foreground color on the markup highlighter
            // specifically to grey out unused symbols
            if (attrs.foregroundColor?.alpha != null && attrs.foregroundColor!!.alpha < 160) {
                hasDimmingEffect = true
                break
            }
        }

        if (hasDimmingEffect) return null
        return syntaxAttrs
    }

    private fun isDimmed(color: Color, attrs: TextAttributes?): Boolean {
        // Check perceived brightness — dimmed/unused text is typically grey
        val brightness = (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue) / 255f
        if (brightness < MIN_BRIGHTNESS) return true

        // Check if the color is very desaturated (grey) — unused code is often
        // rendered in a muted grey regardless of brightness
        val max = maxOf(color.red, color.green, color.blue).toFloat()
        val min = minOf(color.red, color.green, color.blue).toFloat()
        val saturation = if (max == 0f) 0f else (max - min) / max
        if (saturation < 0.08f && brightness < 0.55f) return true

        // Check alpha — some themes dim via transparency
        if (color.alpha < 100) return true

        return false
    }

    private fun paintGlow(
        g2d: Graphics2D,
        text: String,
        x: Int,
        baselineY: Int,
        color: Color,
        isDefaultColor: Boolean = false
    ) {
        // Wide colored bloom
        for (pass in 1..PASSES) {
            val fraction = pass.toFloat() / PASSES
            val alpha = BASE_ALPHA * (1f - fraction * 0.6f)
            val offset = fraction * SPREAD

            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g2d.color = color

            val offsets = listOf(
                Pair(-offset, -offset), Pair(offset, -offset),
                Pair(-offset, offset),  Pair(offset, offset),
                Pair(-offset, 0f),      Pair(offset, 0f),
                Pair(0f, -offset),      Pair(0f, offset)
            )
            for ((dx, dy) in offsets) {
                g2d.drawString(text, x + dx.toInt(), baselineY + dy.toInt())
            }
        }

        // For default-colored tokens (identifiers etc.), paint the core as
        // near-white so they look illuminated rather than just pink.
        // For explicitly colored tokens (keywords etc.), blend their color
        // toward white for a bright but still tinted core.
        val coreColor = if (isDefaultColor) {
            Color(240, 240, 255) // near-white with very slight cool tint
        } else {
            blendTowardWhite(color, 0.6f)
        }
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f)
        g2d.color = coreColor
        g2d.drawString(text, x, baselineY)
    }

    private fun getVisibleLineEnd(foldingModel: FoldingModel, lineStart: Int, lineEnd: Int): Int {
        var paintEnd = lineEnd
        for (region in foldingModel.allFoldRegions) {
            if (!region.isExpanded && region.startOffset > lineStart && region.startOffset <= lineEnd) {
                if (region.startOffset < paintEnd) paintEnd = region.startOffset
            }
        }
        return paintEnd
    }

    private fun isCommentLine(editor: Editor, lineStart: Int, lineEnd: Int): Boolean {
        if (lineStart >= lineEnd) return false
        val iterator = editor.highlighter.createIterator(lineStart)
        while (!iterator.atEnd() && iterator.start < lineEnd) {
            val tokenTypeName = (iterator.tokenType?.toString() ?: "").lowercase()
            val isComment = tokenTypeName.contains("comment")
            val isWhitespace = tokenTypeName.contains("space") || tokenTypeName.contains("white")
            if (!isComment && !isWhitespace) return false
            iterator.advance()
        }
        return true
    }

    private fun isTokenDimmed(editor: Editor, tokenStart: Int, tokenEnd: Int): Boolean {
        val project = editor.project ?: return false
        val file = com.intellij.psi.PsiDocumentManager.getInstance(project)
            .getPsiFile(editor.document) ?: return false

        val highlights = com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
            .getHighlights(editor.document, null, project)

        for (info in highlights) {
            if (info.startOffset <= tokenStart && info.endOffset >= tokenEnd) {
                val attrs = info.getTextAttributes(file, editor.colorsScheme)
                val fg = attrs?.foregroundColor ?: continue
                val brightness = (0.299f * fg.red + 0.587f * fg.green + 0.114f * fg.blue) / 255f
                val max = maxOf(fg.red, fg.green, fg.blue).toFloat()
                val min = minOf(fg.red, fg.green, fg.blue).toFloat()
                val saturation = if (max == 0f) 0f else (max - min) / max
                if (saturation < 0.15f && brightness < 0.6f) return true
                if (fg.alpha < 160) return true
            }
        }
        return false
    }
}

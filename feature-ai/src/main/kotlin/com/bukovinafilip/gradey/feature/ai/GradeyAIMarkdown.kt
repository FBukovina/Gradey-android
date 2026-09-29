package com.bukovinafilip.gradey.feature.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal sealed interface MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Item(val text: String, val marker: String) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
}

/** A bounded native text renderer: no HTML, remote images, or executable content. */
internal object AIMarkdown {
    fun blocks(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }.split('\n')
        val result = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var fence: String? = null
        var language = ""
        fun flush() {
            if (paragraph.isNotEmpty()) result += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
        for (raw in lines) {
            val line = raw.trim()
            val boundary = Regex("^(`{3,}|~{3,})(.*)$").matchEntire(line)
            if (fence != null) {
                if (boundary != null && boundary.groupValues[1].first() == fence.first() && boundary.groupValues[1].length >= fence.length) {
                    result += MarkdownBlock.Code(code.joinToString("\n"), language)
                    code.clear(); fence = null
                } else code += raw
                continue
            }
            if (boundary != null) {
                flush(); fence = boundary.groupValues[1]; language = boundary.groupValues[2].trim(); continue
            }
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            val item = Regex("^([-*+]|[0-9]+[.)])\\s+(.+)$").matchEntire(line)
            when {
                line.isBlank() -> flush()
                heading != null -> { flush(); result += MarkdownBlock.Heading(heading.groupValues[2].trimEnd('#').trim(), heading.groupValues[1].length) }
                item != null -> { flush(); result += MarkdownBlock.Item(item.groupValues[2], item.groupValues[1].let { if (it.length == 1) "•" else it }) }
                else -> paragraph += raw
            }
        }
        flush()
        if (fence != null) result += MarkdownBlock.Code(code.joinToString("\n"), language)
        return result
    }

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        val value = text.replace(Regex("\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)")) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        val token = Regex("(`|\\*\\*|__|\\*|_)(.+?)\\1")
        var index = 0
        token.findAll(value).forEach { match ->
            append(value.substring(index, match.range.first))
            pushStyle(when (match.groupValues[1]) {
                "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                "*", "_" -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> SpanStyle(fontFamily = FontFamily.Monospace)
            })
            append(match.groupValues[2]); pop()
            index = match.range.last + 1
        }
        append(value.substring(index))
    }
}

@Composable
internal fun AIMarkdownText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        remember(text) { AIMarkdown.blocks(text) }.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(AIMarkdown.inline(block.text), fontWeight = FontWeight.SemiBold,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                is MarkdownBlock.Paragraph -> Text(AIMarkdown.inline(block.text), style = MaterialTheme.typography.bodyLarge)
                is MarkdownBlock.Item -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(block.marker, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(AIMarkdown.inline(block.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                is MarkdownBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (block.language.isNotBlank()) Text(block.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
            }
        }
    }
}

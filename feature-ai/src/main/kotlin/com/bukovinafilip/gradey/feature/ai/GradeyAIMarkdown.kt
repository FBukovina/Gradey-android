package com.bukovinafilip.gradey.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular

internal sealed interface MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Item(val text: String, val marker: String) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
}

private val FenceRegex = Regex("^(`{3,}|~{3,})(.*)$")
private val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val ItemRegex = Regex("^([-*+]|[0-9]+[.)])\\s+(.+)$")
private val LinkRegex = Regex("\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)")
private val InlineTokenRegex = Regex("(`|\\*\\*|__|\\*|_)(.+?)\\1")

/** Bullet marker for unordered items; ordered items keep their number. */
internal const val MarkdownBullet = "•"

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
            val boundary = FenceRegex.matchEntire(line)
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
            val heading = HeadingRegex.matchEntire(line)
            val item = ItemRegex.matchEntire(line)
            when {
                line.isBlank() -> flush()
                heading != null -> { flush(); result += MarkdownBlock.Heading(heading.groupValues[2].trimEnd('#').trim(), heading.groupValues[1].length) }
                item != null -> { flush(); result += MarkdownBlock.Item(item.groupValues[2], item.groupValues[1].let { if (it.length == 1) MarkdownBullet else it }) }
                else -> paragraph += raw
            }
        }
        flush()
        if (fence != null) result += MarkdownBlock.Code(code.joinToString("\n"), language)
        return result
    }

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        val value = text.replace(LinkRegex) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        var index = 0
        InlineTokenRegex.findAll(value).forEach { match ->
            append(value.substring(index, match.range.first))
            // Inline emphasis inside a reply; the surrounding block keeps its GradeyType style.
            pushStyle(when (match.groupValues[1]) {
                "**", "__" -> SpanStyle(fontWeight = GradeyType.headline.fontWeight)
                "*", "_" -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> SpanStyle(fontFamily = FontFamily.Monospace)
            })
            append(match.groupValues[2]); pop()
            index = match.range.last + 1
        }
        append(value.substring(index))
    }

    /**
     * Parses blocks and their inline spans in one pass, so a finished reply is never parsed again.
     * Consecutive items form one list, spaced 7 dp apart like iOS.
     */
    fun render(markdown: String): List<RenderedBlock> {
        val result = mutableListOf<RenderedBlock>()
        val items = mutableListOf<RenderedBlock.Item>()
        fun flushItems() {
            if (items.isNotEmpty()) result += RenderedBlock.ItemList(items.toList())
            items.clear()
        }
        for (block in blocks(markdown)) {
            if (block is MarkdownBlock.Item) {
                items += RenderedBlock.Item(inline(block.text), block.marker.takeUnless { it == MarkdownBullet })
                continue
            }
            flushItems()
            result += when (block) {
                is MarkdownBlock.Heading -> RenderedBlock.Heading(inline(block.text), block.level)
                is MarkdownBlock.Paragraph -> RenderedBlock.Paragraph(inline(block.text))
                is MarkdownBlock.Code -> RenderedBlock.Code(block.text, block.language)
                is MarkdownBlock.Item -> error("Handled above")
            }
        }
        flushItems()
        return result
    }
}

/**
 * Rendered replies by text. A completed reply that scrolls out of the list and back is taken from
 * here instead of being parsed again; streaming text is never stored.
 */
internal class AIMarkdownCache(private val capacity: Int = 64) {
    private val entries = object : LinkedHashMap<String, List<RenderedBlock>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RenderedBlock>>?) = size > capacity
    }

    operator fun get(text: String): List<RenderedBlock>? = entries[text]
    fun put(text: String, blocks: List<RenderedBlock>) { if (entries[text] !== blocks) entries[text] = blocks }
}

@Immutable
internal sealed interface RenderedBlock {
    @Immutable data class Heading(val text: AnnotatedString, val level: Int) : RenderedBlock
    @Immutable data class Paragraph(val text: AnnotatedString) : RenderedBlock
    /** [number] is null for a bullet item. */
    @Immutable data class Item(val text: AnnotatedString, val number: String?)
    @Immutable data class ItemList(val items: List<Item>) : RenderedBlock
    @Immutable data class Code(val text: String, val language: String) : RenderedBlock
}

/**
 * Assistant reply body (iOS `GradeyAIMarkdownText`): body text, display headings and gradient bullets.
 * The text is parsed once per value; with a [cache], a [complete] reply is stored and reused when its
 * row is composed again, so finished replies are never re-parsed while the list scrolls.
 */
@Composable
internal fun AIMarkdownText(text: String, modifier: Modifier = Modifier, cache: AIMarkdownCache? = null, complete: Boolean = false) {
    val blocks = remember(text) { cache?.get(text) ?: AIMarkdown.render(text) }
    if (complete && cache != null) SideEffect { cache.put(text, blocks) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val body = remember(onSurface) { GradeyType.body.copy(color = onSurface, lineHeight = ReplyLineHeight) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index) {
                when (block) {
                    is RenderedBlock.Heading -> Text(
                        block.text,
                        Modifier.padding(top = GradeySpacing.xs).semantics { heading() },
                        style = if (block.level <= 2) GradeyType.display(19) else GradeyType.subheadlineSemibold,
                        color = onSurface,
                    )
                    is RenderedBlock.Paragraph -> Text(block.text, style = body)
                    is RenderedBlock.ItemList -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        block.items.forEachIndexed { itemIndex, item -> key(itemIndex) { MarkdownItem(item, body) } }
                    }
                    is RenderedBlock.Code -> MarkdownCode(block)
                }
            }
        }
    }
}

/** iOS `lineSpacing(3)` on 17 pt body text. */
private val ReplyLineHeight = 1.45.em

@Composable
private fun MarkdownItem(item: RenderedBlock.Item, body: TextStyle) {
    val lift = with(LocalDensity.current) { 2.dp.roundToPx() }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (item.number != null) {
            Text(item.number, Modifier.alignByBaseline(), style = GradeyType.bodyMedium.copy(color = MaterialTheme.colorScheme.primary).tabular())
        } else {
            val gradient = MaterialTheme.colorScheme.brandGradient
            // The dot sits just above the first baseline, like iOS's `●` with a 2 pt baseline offset.
            Box(Modifier.alignBy { it.measuredHeight + lift }.size(6.dp).background(gradient, CircleShape))
        }
        Text(item.text, Modifier.weight(1f).alignByBaseline(), style = body)
    }
}

@Composable
private fun MarkdownCode(block: RenderedBlock.Code) {
    val tokens = MaterialTheme.gradeyColors
    Column(
        Modifier.fillMaxWidth().background(tokens.tertiaryFill, RoundedCornerShape(GradeyRadius.sm)).padding(GradeySpacing.md),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (block.language.isNotBlank()) Text(block.language, style = GradeyType.caption2Semibold, color = tokens.secondaryLabel)
        Text(
            block.text,
            Modifier.horizontalScroll(rememberScrollState()),
            style = GradeyType.footnote.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

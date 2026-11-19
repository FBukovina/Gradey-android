package com.bukovinafilip.gradey.feature.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradeyAIMarkdownTest {
    @Test fun preservesOrderedStepsAndCodeWhileRenderingMarkdown() {
        val blocks = AIMarkdown.blocks("## Revision\n\n1. **Read** your notes\n2. Try an example\n\n```kotlin\nval total = 2 * 3\n```\n\nCheck your answer.")
        assertThat(blocks[0]).isEqualTo(MarkdownBlock.Heading("Revision", 2))
        assertThat(blocks[1]).isEqualTo(MarkdownBlock.Item("**Read** your notes", "1."))
        assertThat(blocks[2]).isEqualTo(MarkdownBlock.Item("Try an example", "2."))
        assertThat(blocks[3]).isEqualTo(MarkdownBlock.Code("val total = 2 * 3", "kotlin"))
        assertThat(AIMarkdown.inline("**Read** your `notes`").text).isEqualTo("Read your notes")
    }
    @Test fun incompleteStreamingFenceKeepsContentVisible() {
        val blocks = AIMarkdown.blocks("```python\nprint(2)")
        assertThat(blocks).containsExactly(MarkdownBlock.Code("print(2)", "python"))
    }

    @Test fun consecutiveItemsRenderAsOneListAndKeepTheirNumbers() {
        val rendered = AIMarkdown.render("Plan:\n- **Read** notes\n- Practise\n\nThen\n\n1. Check")
        assertThat(rendered).hasSize(4)
        val bullets = rendered[1] as RenderedBlock.ItemList
        assertThat(bullets.items.map { it.text.text to it.number }).containsExactly("Read notes" to null, "Practise" to null).inOrder()
        assertThat((rendered[3] as RenderedBlock.ItemList).items.single().number).isEqualTo("1.")
    }

    @Test fun cacheKeepsCompletedRepliesAndDropsTheOldest() {
        val cache = AIMarkdownCache(capacity = 2)
        val first = AIMarkdown.render("one")
        cache.put("one", first)
        cache.put("two", AIMarkdown.render("two"))
        assertThat(cache["one"]).isSameInstanceAs(first)
        cache.put("three", AIMarkdown.render("three"))
        assertThat(cache["two"]).isNull()
        assertThat(cache["one"]).isSameInstanceAs(first)
    }
}

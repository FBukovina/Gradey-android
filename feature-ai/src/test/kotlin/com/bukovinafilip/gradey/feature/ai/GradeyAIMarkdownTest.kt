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
}

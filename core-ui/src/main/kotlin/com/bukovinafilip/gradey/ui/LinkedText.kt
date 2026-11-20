package com.bukovinafilip.gradey.ui

import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * School text with its web and e-mail addresses made tappable (iOS `HomeworkText.linkified`).
 * Homework and Komens messages both arrive as plain text that mentions links.
 */
fun linkified(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    val taken = ArrayList<IntRange>()
    val mail = Patterns.EMAIL_ADDRESS.matcher(text)
    while (mail.find()) {
        addLink(LinkAnnotation.Url("mailto:" + mail.group(), styles), mail.start(), mail.end())
        taken += mail.start() until mail.end()
    }
    val web = Patterns.WEB_URL.matcher(text)
    while (web.find()) {
        // The domain of an e-mail address is part of that address, not a link of its own.
        if (taken.any { web.start() <= it.last && it.first < web.end() }) continue
        val shown = web.group()
        // A link written without a scheme opens over https.
        addLink(LinkAnnotation.Url(if (shown.contains("://")) shown else "https://$shown", styles), web.start(), web.end())
    }
}

/*
 * Copyright (c) 2022 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.element.android.libraries.designsystem.components

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.text.util.LinkifyCompat
import io.element.android.libraries.designsystem.preview.ElementThemedPreview
import io.element.android.libraries.designsystem.preview.PreviewGroup
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.theme.LinkColor
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

const val LINK_TAG = "URL"

@Composable
fun ClickableLinkText(
    text: String,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    linkify: Boolean = true,
    linkAnnotationTag: String = LINK_TAG,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
    inlineContent: ImmutableMap<String, InlineTextContent> = persistentMapOf(),
) {
    ClickableLinkText(
        annotatedString = AnnotatedString(text),
        interactionSource = interactionSource,
        modifier = modifier,
        linkify = linkify,
        linkAnnotationTag = linkAnnotationTag,
        onClick = onClick,
        onLongClick = onLongClick,
        style = style,
        inlineContent = inlineContent,
    )
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun ClickableLinkText(
    annotatedString: AnnotatedString,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    linkify: Boolean = true,
    linkAnnotationTag: String = LINK_TAG,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
    inlineContent: ImmutableMap<String, InlineTextContent> = persistentMapOf(),
) {
    @Suppress("NAME_SHADOWING")
    val annotatedString = remember(annotatedString) {
        if (linkify) {
            annotatedString.linkify(SpanStyle(color = LinkColor))
        } else {
            annotatedString
        }
    }
    val uriHandler = LocalUriHandler.current
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val pressIndicator = Modifier.pointerInput(onClick) {
        detectTapGestures(
            onPress = { offset: Offset ->
                val pressInteraction = PressInteraction.Press(offset)
                interactionSource.emit(pressInteraction)
                val isReleased = tryAwaitRelease()
                if (isReleased) {
                    interactionSource.emit(PressInteraction.Release(pressInteraction))
                } else {
                    interactionSource.emit(PressInteraction.Cancel(pressInteraction))
                }
            },
            onLongPress = {
                onLongClick()
            }
        ) { offset ->
            layoutResult.value?.let { layoutResult ->
                val position = layoutResult.getOffsetForPosition(offset)
                val linkUrlAnnotations = annotatedString.getUrlAnnotations(position, position)
                    .map { AnnotatedString.Range(it.item.url, it.start, it.end, linkAnnotationTag) }
                val linkStringAnnotations = linkUrlAnnotations +
                    annotatedString.getStringAnnotations(linkAnnotationTag, position, position)
                if (linkStringAnnotations.isEmpty()) {
                    onClick()
                } else {
                    uriHandler.openUri(linkStringAnnotations.first().item)
                }
            }
        }
    }
    Text(
        text = annotatedString,
        modifier = modifier.then(pressIndicator),
        style = style,
        onTextLayout = {
            layoutResult.value = it
        },
        inlineContent = inlineContent,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalTextApi::class)
fun AnnotatedString.linkify(linkStyle: SpanStyle): AnnotatedString {
    val original = this
    val spannable = SpannableString(this.text)
    LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)

    val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
    return buildAnnotatedString {
        append(original)
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (original.getUrlAnnotations(start, end).isEmpty() && original.getStringAnnotations("URL", start, end).isEmpty()) {
                // Prevent linkifying domains in user or room handles (@user:domain.com, #room:domain.com)
                if (start > 0 && !spannable[start - 1].isWhitespace()) continue
                addStyle(
                    start = start,
                    end = end,
                    style = linkStyle,
                )
                addStringAnnotation(
                    tag = LINK_TAG,
                    annotation = span.url,
                    start = start,
                    end = end
                )
            }
        }
    }
}

@Preview(group = PreviewGroup.Text)
@Composable
internal fun ClickableLinkTextPreview() =
    ElementThemedPreview { ContentToPreview() }

@Composable
private fun ContentToPreview() {
    ClickableLinkText(
        annotatedString = AnnotatedString("Hello", ParagraphStyle()),
        linkAnnotationTag = "",
        onClick = {},
        onLongClick = {},
        interactionSource = remember { MutableInteractionSource() },
    )
}


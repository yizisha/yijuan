package com.flowreader.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.core.designsystem.token.FlowEditorialFont
import com.flowreader.app.core.designsystem.token.FlowRadius
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.CoverArt
import com.flowreader.core.R
import java.io.File

/**
 * The one cover renderer.
 *
 * Callers pass a path, not a painter: resolving the file is Coil's job and happens off the main
 * thread, which is why the pre-v53 `File(path).exists()` check inside composition is gone. Books
 * without artwork fall back to a [CoverArt]-seeded gradient instead of a shared grey glyph.
 */
@Composable
fun BookCover(
    title: String,
    modifier: Modifier = Modifier,
    author: String = "",
    coverPath: String? = null,
    shape: Shape = RoundedCornerShape(FlowRadius.sm),
    showTitleOnFallback: Boolean = true
) {
    val description = stringResource(R.string.flow_cover_description, title)

    Box(modifier = modifier.clip(shape)) {
        if (coverPath.isNullOrBlank()) {
            GeneratedCover(title = title, author = author, showTitle = showTitleOnFallback, description = description)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(coverPath))
                    .crossfade(true)
                    .memoryCacheKey(coverPath)
                    .diskCacheKey(coverPath)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                loading = { SkeletonBox(modifier = Modifier.fillMaxSize(), shape = shape) },
                error = {
                    GeneratedCover(
                        title = title,
                        author = author,
                        showTitle = showTitleOnFallback,
                        description = description
                    )
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Skeuomorphic book cover for books without embedded artwork (TXT, etc.):
 * - Off-white paper background
 * - Skeuomorphic book spine shadow and crease groove
 * - Full title prominently printed with elegant typography
 * - Author and subtle book ornament
 *
 * The type scales with the box: the shelf grid can be 2–5 columns wide, and a fixed sp size made
 * the title overflow its tile at 4–5 columns.
 */
@Composable
private fun GeneratedCover(title: String, author: String, showTitle: Boolean, description: String) {
    val colors = MaterialTheme.colorScheme
    val (paper, ink) = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
        colors.surfaceContainerHigh to colors.onSurface
    )[Math.floorMod(title.hashCode(), 4)]
    if (com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current == com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE) {
        val art = if (CoverArt.paletteIndex(title, 2) == 0) R.drawable.clean_cover_pagoda else R.drawable.clean_cover_mountains
        val ink = Color(0xFF202932)
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color(0xFFF8F5EE)).drawWithContent {
                drawContent()
                drawRect(Color(0x12000000), style = Stroke(0.5.dp.toPx()))
            }.clearAndSetSemantics {
                contentDescription = description
            }
        ) {
            // Decode the large shared artwork off the UI thread, sampled to the actual tile.
            AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (maxWidth >= 28.dp || showTitle) {
                // Cover lettering is part of the artwork: fit it to the physical tile; surrounding
                // shelf text still follows accessibility font scaling and carries the full title.
                val fontScale = LocalDensity.current.fontScale
                val tileWidth = maxWidth
                val titleSize = (maxWidth.value * .14f).coerceIn(8f, 22f)
                val authorSize = (maxWidth.value * .085f).coerceIn(7f, 11f)
                val inset = (maxWidth.value * .11f).coerceIn(4f, 16f).dp
                Column(
                    Modifier.fillMaxWidth().heightIn(max = maxHeight * .68f).padding(inset),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        title,
                        color = ink,
                        fontFamily = FlowEditorialFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = (titleSize / fontScale).sp,
                        lineHeight = (titleSize * 1.25f / fontScale).sp,
                        textAlign = TextAlign.Start,
                        maxLines = if (showTitle) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showTitle && tileWidth >= 72.dp && author.isNotBlank()) {
                        Box(Modifier.padding(vertical = 5.dp).width(tileWidth * .2f).height(0.5.dp).background(ink.copy(alpha = .25f)))
                        Text(
                            author,
                            color = ink.copy(alpha = .7f),
                            fontSize = (authorSize / fontScale).sp,
                            lineHeight = (authorSize * 1.3f / fontScale).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        return
    }
    val paperGradient = listOf(paper, paper)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // 1. Off-white paper background
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = paperGradient,
                        startY = 0f,
                        endY = size.height
                    )
                )

                // 2. Spine shadow on left edge
                val spineWidth = size.width * 0.13f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0x33000000),
                            Color(0x18000000),
                            Color(0x06000000),
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = spineWidth
                    )
                )

                // 3. Spine crease fold line
                val creaseX = size.width * 0.06f
                drawLine(
                    color = Color(0x22000000),
                    start = Offset(creaseX, 0f),
                    end = Offset(creaseX, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0x25FFFFFF),
                    start = Offset(creaseX + 1.dp.toPx(), 0f),
                    end = Offset(creaseX + 1.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx()
                )

                // 4. Subtle outer border
                drawRect(
                    color = Color(0x1A000000),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .clearAndSetSemantics { contentDescription = description }
    ) {
        // Scale the printed title with the tile width (2–5 columns) instead of fixed sp. Read the
        // constraint here: inside Column's scope the receiver shadows BoxWithConstraintsScope.
        val tileWidth = maxWidth.value
        if (com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current == com.flowreader.app.domain.model.AppVisualStyle.WARM_PAPER) {
            PaperLandscape(Modifier.fillMaxWidth().height(maxHeight * .36f).align(Alignment.BottomCenter), ink.copy(alpha = .4f))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val titleFontSize = (tileWidth * 0.115f).coerceIn(8f, 16f).sp
            val titleLineHeight = (titleFontSize.value * 1.28f).sp
            val ornamentWidth = (tileWidth * 0.18f).coerceIn(10f, 24f).dp
            val authorFontSize = (tileWidth * 0.085f).coerceIn(7f, 11f).sp

            Text(
                text = title,
                color = ink,
                fontFamily = FlowEditorialFont,
                fontWeight = FontWeight.Medium,
                fontSize = titleFontSize,
                lineHeight = titleLineHeight,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Box(
                modifier = Modifier
                    .padding(vertical = 5.dp)
                    .width(ornamentWidth)
                    .height(1.dp)
                    .background(ink.copy(alpha = .2f))
            )

            if (author.isNotBlank()) {
                Text(
                    text = author,
                    color = ink.copy(alpha = .8f),
                    fontSize = authorFontSize,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@FlowComponentPreviews
@Composable
private fun BookCoverPreview() {
    FlowTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(FlowSpacing.md),
            modifier = Modifier.padding(FlowSpacing.md)
        ) {
            BookCover(title = "心流", author = "米哈里", modifier = Modifier.size(70.dp, 100.dp))
            BookCover(title = "Deep Work", author = "Cal Newport", modifier = Modifier.size(70.dp, 100.dp))
            BookCover(title = "断舍离", modifier = Modifier.size(70.dp, 100.dp), showTitleOnFallback = false)
        }
    }
}

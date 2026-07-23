package com.rolling7.solar

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Sistem vizual hibrid: structura si datele raman native, iar resursele raster sunt folosite
 * exclusiv pentru material, patina si miniaturi decorative fara valori lipite in imagine.
 */
internal val RetroSage = Color(0xFFACCC78)
internal val RetroOlive = Color(0xFF81795A)
internal val RetroYellow = Color(0xFFF1E169)
internal val RetroRed = Color(0xFFDF6A59)
internal val RetroHouseBlue = Color(0xFF9BC3CF)
internal val RetroBackground = Color(0xFF11120D)
internal val RetroPanel = Color(0xFF3A3927)
internal val RetroPanelRaised = Color(0xFF4B4930)
internal val RetroInstrument = Color(0xFF17180F)
internal val RetroText = Color(0xFFE9E1C1)
internal val RetroMuted = Color(0xFFBEB38E)
internal val RetroLine = Color(0xFF6C6747)
internal val RetroBrassDark = Color(0xFF39331C)
internal val RetroBrass = Color(0xFF81795A)
internal val RetroBrassLight = Color(0xFFC7B46D)
internal val RetroPatina = Color(0xFF4F6B57)

internal val RetroMono: FontFamily = FontFamily.Monospace
internal val RetroSerif: FontFamily = FontFamily.Serif

private val PlateShape = RoundedCornerShape(18.dp)
private const val RetroBackdropTextureEnabled = true
private const val RetroSurfaceTextureEnabled = true

@Composable
internal fun RetroIndustrialBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.background(RetroBackground)) {
        if (RetroBackdropTextureEnabled) {
            Image(
                painter = painterResource(R.drawable.retro_metal_texture),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.43f
            )
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF15160F).copy(alpha = 0.34f), RetroBackground.copy(alpha = 0.64f), Color(0xFF080906).copy(alpha = 0.82f))
                )
            )

            // Pete mari, foarte discrete, care sparg uniformitatea texturii fotografice.
            repeat(22) { index ->
                val x = noise(index, 2.3f) * size.width
                val y = noise(index, 8.9f) * size.height
                val radius = (18f + noise(index, 5.1f) * 72f) * density
                drawCircle(
                    color = if (index % 3 == 0) {
                        RetroPatina.copy(alpha = 0.030f)
                    } else {
                        Color.Black.copy(alpha = 0.035f)
                    },
                    radius = radius,
                    center = Offset(x, y)
                )
            }

            // Umbra asimetrica face fundalul sa para iluminat din stanga-sus.
            drawRect(
                brush = Brush.linearGradient(
                    listOf(Color(0xFFF1E169).copy(alpha = 0.025f), Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
        }
    }
}

@Composable
internal fun RetroPanelSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 17.dp, vertical = 15.dp),
    fillContent: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PlateShape,
        color = RetroPanel,
        border = BorderStroke(1.dp, RetroBrassLight.copy(alpha = 0.66f)),
        shadowElevation = 12.dp
    ) {
        Box {
            RetroMetalTexture(
                modifier = Modifier.matchParentSize().clip(PlateShape),
                base = RetroPanel,
                intensity = 1f
            )
            Column(
                (if (fillContent) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .padding(contentPadding),
                verticalArrangement = verticalArrangement,
                content = content
            )
            RetroPlateFrame(Modifier.matchParentSize(), PlateShape)
            RetroCornerScrews(Modifier.matchParentSize())
        }
    }
}

@Composable
internal fun RetroInsetSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = RetroInstrument,
        border = BorderStroke(1.dp, RetroBrassLight.copy(alpha = 0.58f)),
        shadowElevation = 1.dp
    ) {
        Box {
            RetroInstrumentTexture(Modifier.matchParentSize().clip(shape))
            content()
            RetroInsetFrame(Modifier.matchParentSize(), shape)
        }
    }
}

/** Strat cald, patat si zgariat pentru cadran; scala si toate valorile raman desenate nativ. */
@Composable
internal fun RetroGaugePaperTexture(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(9.dp)
    Box(modifier.clip(shape).background(Color(0xFF4A462D))) {
        if (RetroSurfaceTextureEnabled) {
            Image(
                painter = painterResource(R.drawable.retro_metal_texture),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.34f
            )
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF91855A).copy(alpha = 0.30f),
                        Color(0xFF4A462D).copy(alpha = 0.42f),
                        Color.Black.copy(alpha = 0.68f)
                    ),
                    center = Offset(size.width * 0.34f, size.height * 0.22f),
                    radius = size.maxDimension * 0.78f
                )
            )
            repeat(15) { index ->
                val center = Offset(noise(index, 17.3f) * size.width, noise(index, 28.9f) * size.height)
                drawCircle(
                    color = if (index % 3 == 0) {
                        Color(0xFF342012).copy(alpha = 0.055f)
                    } else {
                        RetroPatina.copy(alpha = 0.038f)
                    },
                    radius = (5f + noise(index, 33.4f) * 26f) * density,
                    center = center
                )
            }
            repeat(19) { index ->
                val x = noise(index, 41.7f) * size.width
                val y = noise(index, 52.6f) * size.height
                val length = (6f + noise(index, 63.9f) * 28f) * density
                drawLine(
                    color = if (index % 4 == 0) {
                        RetroBrassLight.copy(alpha = 0.10f)
                    } else {
                        Color.Black.copy(alpha = 0.13f)
                    },
                    start = Offset(x, y),
                    end = Offset((x + length).coerceAtMost(size.width), y + noise(index, 72.2f) * 1.4f * density),
                    strokeWidth = (0.35f + noise(index, 81.5f) * 0.7f) * density,
                    cap = StrokeCap.Round
                )
            }
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF1E169).copy(alpha = 0.08f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.32f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
        }
    }
}

@Composable
internal fun RetroMetalButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    accent: Color = RetroYellow,
    description: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) RetroPanelRaised else RetroPanel,
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.90f) else RetroBrass.copy(alpha = 0.78f)
        ),
        shadowElevation = if (selected) 7.dp else 3.dp
    ) {
        Box {
            RetroMetalTexture(
                modifier = Modifier.matchParentSize().clip(shape),
                base = if (selected) RetroPanelRaised else RetroPanel,
                intensity = if (selected) 0.82f else 0.62f
            )
            content()
            RetroReliefEdges(Modifier.matchParentSize(), shape, subtle = true)
            RetroMiniScrews(Modifier.matchParentSize())
            if (selected) {
                Canvas(Modifier.matchParentSize()) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, accent, Color.Transparent)
                        ),
                        start = Offset(size.width * 0.20f, size.height - 2.dp.toPx()),
                        end = Offset(size.width * 0.80f, size.height - 2.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = accent.copy(alpha = 0.20f),
                        start = Offset(size.width * 0.12f, size.height - 4.dp.toPx()),
                        end = Offset(size.width * 0.88f, size.height - 4.dp.toPx()),
                        strokeWidth = 7.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
internal fun RetroLed(
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.25f
        drawCircle(Color.Black.copy(alpha = 0.78f), radius * 1.45f, center)
        drawCircle(RetroBrassLight.copy(alpha = 0.70f), radius * 1.17f, center)
        if (active) {
            drawCircle(color.copy(alpha = 0.08f), radius * 3.0f, center)
            drawCircle(color.copy(alpha = 0.16f), radius * 2.15f, center)
            drawCircle(color.copy(alpha = 0.30f), radius * 1.55f, center)
        }
        drawCircle(if (active) color else RetroOlive, radius, center)
        drawCircle(Color.White.copy(alpha = if (active) 0.72f else 0.16f), radius * 0.28f, center - Offset(radius * 0.30f, radius * 0.30f))
    }
}

@Composable
internal fun RetroSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier,
        color = RetroText,
        fontFamily = RetroMono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp
    )
}

/** Afisaj VFD cu sticla, segmente fatetate, scanlines si glow in mai multe straturi. */
@Composable
internal fun RetroVfdDisplay(
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    minDigits: Int = 1,
    embedded: Boolean = false,
    description: String = "$value $unit"
) {
    val shown = if (value == "—") value else value.padStart(minDigits, '0')
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
    ) {
        val corner = 8.dp.toPx()
        if (!embedded) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF050805), Color(0xFF10170C), Color(0xFF030503))
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner)
            )
        }

        // Urma foarte discreta de fosfor ars si lumina inegala a unui tub vechi.
        drawOval(
            color = color.copy(alpha = 0.022f),
            topLeft = Offset(size.width * 0.12f, size.height * 0.20f),
            size = Size(size.width * 0.66f, size.height * 0.58f)
        )
        drawOval(
            color = Color(0xFFB9A45D).copy(alpha = 0.018f),
            topLeft = Offset(size.width * 0.56f, size.height * 0.08f),
            size = Size(size.width * 0.34f, size.height * 0.78f)
        )

        repeat(14) { row ->
            val y = size.height * (row + 1) / 15f
            drawLine(
                color = color.copy(alpha = 0.018f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 0.55.dp.toPx()
            )
        }
        repeat(22) { index ->
            drawCircle(
                color = if (index % 2 == 0) color.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.018f),
                radius = (0.25f + noise(index, 42f) * 0.55f) * density,
                center = Offset(noise(index, 31f) * size.width, noise(index, 67f) * size.height)
            )
        }

        val horizontalPadding = 8.dp.toPx()
        val unitSpace = if (unit.isBlank()) 0f else size.height * 0.42f
        val digitArea = (size.width - horizontalPadding * 2f - unitSpace).coerceAtLeast(1f)
        val units = shown.sumOf { if (it == '.') 0.32 else if (it == '+' || it == '-') 0.68 else 1.0 }.toFloat()
            .coerceAtLeast(1f)
        val cellWidth = min(digitArea / units, size.height * 0.61f)
        val usedWidth = cellWidth * units
        var cursor = horizontalPadding + (digitArea - usedWidth) / 2f
        val top = size.height * 0.14f
        val bottom = size.height * 0.84f
        val middle = (top + bottom) / 2f
        val segmentLength = cellWidth * 0.66f
        val thickness = min(cellWidth * 0.125f, size.height * 0.075f).coerceAtLeast(1.4.dp.toPx())

        shown.forEach { character ->
            if (character == '.') {
                val width = cellWidth * 0.32f
                val dot = Offset(cursor + width * 0.48f, bottom - thickness * 0.10f)
                drawCircle(color.copy(alpha = 0.12f), thickness * 1.9f, dot)
                drawCircle(color.copy(alpha = 0.30f), thickness * 1.25f, dot)
                drawCircle(color, thickness * 0.68f, dot)
                drawCircle(Color.White.copy(alpha = 0.58f), thickness * 0.18f, dot - Offset(thickness * 0.17f, thickness * 0.17f))
                cursor += width
                return@forEach
            }

            val cellFactor = if (character == '+' || character == '-') 0.68f else 1f
            val actualWidth = cellWidth * cellFactor
            val centerX = cursor + actualWidth / 2f
            val half = min(segmentLength * cellFactor, actualWidth * 0.72f) / 2f
            val left = centerX - half
            val right = centerX + half
            val verticalTop = top + thickness * 0.72f
            val verticalBottom = bottom - thickness * 0.72f
            val active = vfdSegments(character)

            fun horizontal(name: Char, y: Float) {
                drawVfdSegment(
                    active = name in active,
                    color = color,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    thickness = thickness,
                    horizontal = true
                )
            }

            fun vertical(name: Char, x: Float, y1: Float, y2: Float) {
                drawVfdSegment(
                    active = name in active,
                    color = color,
                    start = Offset(x, y1),
                    end = Offset(x, y2),
                    thickness = thickness,
                    horizontal = false
                )
            }

            horizontal('a', top)
            horizontal('g', middle)
            horizontal('d', bottom)
            vertical('f', left, verticalTop, middle - thickness * 0.62f)
            vertical('b', right, verticalTop, middle - thickness * 0.62f)
            vertical('e', left, middle + thickness * 0.62f, verticalBottom)
            vertical('c', right, middle + thickness * 0.62f, verticalBottom)
            if (character == '+') {
                drawVfdSegment(true, color, Offset(centerX, middle - actualWidth * 0.22f), Offset(centerX, middle + actualWidth * 0.22f), thickness, false)
            }
            cursor += actualWidth
        }

        if (unit.isNotBlank()) {
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                textSize = size.height * 0.30f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(8f * density, 0f, 0f, color.copy(alpha = 0.65f).toArgb())
            }
            drawContext.canvas.nativeCanvas.drawText(
                unit,
                size.width - horizontalPadding - unitSpace / 2f,
                size.height * 0.67f,
                unitPaint
            )
        }

        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (embedded) 0.045f else 0.10f),
                    Color.Transparent,
                    Color.Black.copy(alpha = if (embedded) 0.08f else 0.20f)
                )
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner)
        )
        if (!embedded) {
            drawRoundRect(
                color = RetroBrassDark,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                style = Stroke(2.3.dp.toPx())
            )
            drawRoundRect(
                color = RetroBrassLight.copy(alpha = 0.62f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner - 2.dp.toPx(), corner - 2.dp.toPx()),
                style = Stroke(0.8.dp.toPx())
            )
        }
    }
}

@Composable
internal fun RetroReliefEdges(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp),
    subtle: Boolean = false
) {
    Canvas(modifier.clip(shape)) {
        val inset = if (subtle) 2.5.dp.toPx() else 5.dp.toPx()
        val stroke = if (subtle) 0.8.dp.toPx() else 1.2.dp.toPx()
        val light = RetroBrassLight.copy(alpha = if (subtle) 0.26f else 0.38f)
        val dark = Color.Black.copy(alpha = if (subtle) 0.48f else 0.68f)
        drawLine(light, Offset(inset, inset), Offset(size.width - inset, inset), stroke)
        drawLine(light, Offset(inset, inset), Offset(inset, size.height - inset), stroke)
        drawLine(dark, Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset), stroke)
        drawLine(dark, Offset(size.width - inset, inset), Offset(size.width - inset, size.height - inset), stroke)
    }
}

@Composable
internal fun RetroCornerScrews(modifier: Modifier = Modifier, inset: Dp = 12.dp) {
    Canvas(modifier) {
        val edge = inset.toPx()
        val centers = listOf(
            Offset(edge, edge),
            Offset(size.width - edge, edge),
            Offset(edge, size.height - edge),
            Offset(size.width - edge, size.height - edge)
        )
        centers.forEachIndexed { index, center -> drawIndustrialScrew(center, 4.1.dp.toPx(), index) }
    }
}

@Composable
internal fun RetroMiniScrews(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val edge = 7.dp.toPx()
        val centers = listOf(
            Offset(edge, edge),
            Offset(size.width - edge, edge),
            Offset(edge, size.height - edge),
            Offset(size.width - edge, size.height - edge)
        )
        centers.forEachIndexed { index, center -> drawIndustrialScrew(center, 2.5.dp.toPx(), index + 1) }
    }
}

@Composable
private fun RetroMetalTexture(modifier: Modifier, base: Color, intensity: Float) {
    Box(modifier.background(base)) {
        if (RetroSurfaceTextureEnabled) {
            Image(
                painter = painterResource(R.drawable.retro_metal_texture),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = (0.50f * intensity).coerceIn(0f, 0.56f)
            )
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        base.copy(alpha = 0.16f),
                        Color.Transparent,
                        RetroBrassDark.copy(alpha = 0.32f),
                        base.copy(alpha = 0.30f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
            repeat(28) { index ->
                val y = noise(index, 3.7f) * size.height
                val x = noise(index, 9.2f) * size.width
                val length = size.width * (0.04f + noise(index, 19.6f) * 0.24f)
                drawLine(
                    color = if (index % 3 == 0) {
                        RetroBrassLight.copy(alpha = 0.030f * intensity)
                    } else {
                        Color.Black.copy(alpha = 0.050f * intensity)
                    },
                    start = Offset(x, y),
                    end = Offset((x + length).coerceAtMost(size.width), y),
                    strokeWidth = (0.35f + noise(index, 7.2f) * 0.85f) * density
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.075f), Color.Transparent, Color.Black.copy(alpha = 0.25f))
                )
            )
        }
    }
}

@Composable
private fun RetroInstrumentTexture(modifier: Modifier) {
    Box(modifier.background(RetroInstrument)) {
        if (RetroSurfaceTextureEnabled) {
            Image(
                painter = painterResource(R.drawable.retro_metal_texture),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.19f
            )
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF242518).copy(alpha = 0.52f), RetroInstrument.copy(alpha = 0.72f), Color(0xFF050604).copy(alpha = 0.90f)),
                    center = Offset(size.width * 0.42f, size.height * 0.31f),
                    radius = size.maxDimension * 0.77f
                )
            )
            val lineGap = 4.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = RetroBrassLight.copy(alpha = 0.018f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 0.55.dp.toPx()
                )
                y += lineGap
            }
        }
    }
}

@Composable
private fun RetroPlateFrame(modifier: Modifier, shape: RoundedCornerShape) {
    Canvas(modifier.clip(shape)) {
        val radius = 18.dp.toPx()
        listOf(
            Triple(3.dp.toPx(), 1.1.dp.toPx(), RetroBrassLight.copy(alpha = 0.70f)),
            Triple(6.dp.toPx(), 1.5.dp.toPx(), Color.Black.copy(alpha = 0.72f)),
            Triple(8.dp.toPx(), 0.7.dp.toPx(), RetroBrass.copy(alpha = 0.58f))
        ).forEach { (inset, width, color) ->
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius - inset, radius - inset),
                style = Stroke(width)
            )
        }
    }
}

@Composable
private fun RetroInsetFrame(modifier: Modifier, shape: RoundedCornerShape) {
    Canvas(modifier.clip(shape)) {
        val radius = 12.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.82f),
            style = Stroke(4.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = RetroBrassLight.copy(alpha = 0.38f),
            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
            style = Stroke(0.8.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius - 3.dp.toPx(), radius - 3.dp.toPx())
        )
    }
}

private fun DrawScope.drawIndustrialScrew(center: Offset, radius: Float, index: Int) {
    drawCircle(Color.Black.copy(alpha = 0.62f), radius * 1.38f, center + Offset(radius * 0.18f, radius * 0.28f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(RetroBrassLight, RetroBrass, RetroBrassDark, Color.Black),
            center = center - Offset(radius * 0.28f, radius * 0.30f),
            radius = radius * 1.35f
        ),
        radius = radius,
        center = center
    )
    drawCircle(Color.Black.copy(alpha = 0.62f), radius, center, style = Stroke(radius * 0.16f))
    val angle = if (index % 2 == 0) 0.74f else -0.62f
    val dx = kotlin.math.cos(angle) * radius * 0.62f
    val dy = kotlin.math.sin(angle) * radius * 0.62f
    drawLine(
        color = Color.Black.copy(alpha = 0.82f),
        start = center - Offset(dx, dy),
        end = center + Offset(dx, dy),
        strokeWidth = radius * 0.24f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = RetroBrassLight.copy(alpha = 0.34f),
        start = center - Offset(dx, dy) - Offset(0f, radius * 0.10f),
        end = center + Offset(dx, dy) - Offset(0f, radius * 0.10f),
        strokeWidth = radius * 0.08f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawVfdSegment(
    active: Boolean,
    color: Color,
    start: Offset,
    end: Offset,
    thickness: Float,
    horizontal: Boolean
) {
    if (active) {
        drawLine(color.copy(alpha = 0.08f), start, end, thickness * 2.8f, StrokeCap.Round)
        drawLine(color.copy(alpha = 0.19f), start, end, thickness * 1.75f, StrokeCap.Round)
    }
    val wear = 0.82f + ((sin(start.x * 0.071f + start.y * 0.053f) + 1f) * 0.07f)
    val fill = if (active) color.copy(alpha = wear) else color.copy(alpha = 0.045f)
    val bevel = thickness * 0.48f
    val path = Path().apply {
        if (horizontal) {
            moveTo(start.x, start.y)
            lineTo(start.x + bevel, start.y - thickness / 2f)
            lineTo(end.x - bevel, end.y - thickness / 2f)
            lineTo(end.x, end.y)
            lineTo(end.x - bevel, end.y + thickness / 2f)
            lineTo(start.x + bevel, start.y + thickness / 2f)
        } else {
            moveTo(start.x, start.y)
            lineTo(start.x + thickness / 2f, start.y + bevel)
            lineTo(end.x + thickness / 2f, end.y - bevel)
            lineTo(end.x, end.y)
            lineTo(end.x - thickness / 2f, end.y - bevel)
            lineTo(start.x - thickness / 2f, start.y + bevel)
        }
        close()
    }
    drawPath(path, fill)
    if (active) {
        drawLine(
            Color.White.copy(alpha = 0.33f),
            if (horizontal) start + Offset(bevel, -thickness * 0.18f) else start + Offset(-thickness * 0.18f, bevel),
            if (horizontal) end - Offset(bevel, thickness * 0.18f) else end - Offset(thickness * 0.18f, bevel),
            thickness * 0.10f,
            StrokeCap.Round
        )
    }
}

private fun vfdSegments(character: Char): Set<Char> = when (character) {
    '0' -> setOf('a', 'b', 'c', 'd', 'e', 'f')
    '1' -> setOf('b', 'c')
    '2' -> setOf('a', 'b', 'd', 'e', 'g')
    '3' -> setOf('a', 'b', 'c', 'd', 'g')
    '4' -> setOf('b', 'c', 'f', 'g')
    '5' -> setOf('a', 'c', 'd', 'f', 'g')
    '6' -> setOf('a', 'c', 'd', 'e', 'f', 'g')
    '7' -> setOf('a', 'b', 'c')
    '8' -> setOf('a', 'b', 'c', 'd', 'e', 'f', 'g')
    '9' -> setOf('a', 'b', 'c', 'd', 'f', 'g')
    '-', '—' -> setOf('g')
    '+' -> setOf('g')
    else -> emptySet()
}

private fun noise(index: Int, salt: Float): Float {
    val raw = sin((index + 1) * 12.9898 + salt * 78.233) * 43_758.5453
    return (raw - floor(raw)).toFloat().let { if (it < 0f) it + 1f else it }
}

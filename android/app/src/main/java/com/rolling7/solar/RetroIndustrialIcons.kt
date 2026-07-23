package com.rolling7.solar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal enum class RetroFlowAsset {
    SOLAR,
    BATTERY,
    HOUSE,
    GRID
}

/** Miniaturi raster extrase individual din referinta aprobata; nu contin valori live. */
@Composable
internal fun RetroIsometricAsset(
    asset: RetroFlowAsset,
    modifier: Modifier = Modifier
) {
    val resource = when (asset) {
        RetroFlowAsset.SOLAR -> R.drawable.retro_flow_solar
        RetroFlowAsset.BATTERY -> R.drawable.retro_flow_battery
        RetroFlowAsset.HOUSE -> R.drawable.retro_flow_house
        RetroFlowAsset.GRID -> R.drawable.retro_flow_grid
    }
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

@Composable
internal fun RetroNavigationGlyph(
    tab: RetroTab,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        when (tab) {
            RetroTab.DASHBOARD -> drawGaugeGlyph(color)
            RetroTab.ENERGY -> drawLightningGlyph(color)
            RetroTab.SYSTEM -> drawGearGlyph(color)
            RetroTab.SETTINGS -> drawSlidersGlyph(color)
        }
    }
}

private fun DrawScope.drawGaugeGlyph(color: Color) {
    val radius = min(size.width, size.height) * 0.34f
    val center = Offset(size.width / 2f, size.height * 0.57f)
    drawArc(
        color = color.copy(alpha = 0.90f),
        startAngle = 205f,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(radius * 0.09f, cap = StrokeCap.Round)
    )
    repeat(7) { index ->
        val angle = Math.toRadians((205.0 + index * 130.0 / 6.0))
        val start = center + Offset(cos(angle).toFloat() * radius * 0.76f, sin(angle).toFloat() * radius * 0.76f)
        val end = center + Offset(cos(angle).toFloat() * radius * 0.96f, sin(angle).toFloat() * radius * 0.96f)
        drawLine(color, start, end, radius * 0.055f, StrokeCap.Round)
    }
    val needleAngle = Math.toRadians(244.0)
    drawLine(color, center, center + Offset(cos(needleAngle).toFloat() * radius * 0.72f, sin(needleAngle).toFloat() * radius * 0.72f), radius * 0.09f, StrokeCap.Round)
    drawCircle(color, radius * 0.13f, center)
}

private fun DrawScope.drawLightningGlyph(color: Color) {
    val p = Path().apply {
        moveTo(size.width * 0.58f, size.height * 0.08f)
        lineTo(size.width * 0.24f, size.height * 0.56f)
        lineTo(size.width * 0.48f, size.height * 0.52f)
        lineTo(size.width * 0.37f, size.height * 0.93f)
        lineTo(size.width * 0.79f, size.height * 0.40f)
        lineTo(size.width * 0.55f, size.height * 0.44f)
        close()
    }
    drawPath(p, Color.Black.copy(alpha = 0.42f), style = Stroke(size.minDimension * 0.10f))
    drawPath(p, color)
    drawLine(Color.White.copy(alpha = 0.34f), Offset(size.width * 0.56f, size.height * 0.16f), Offset(size.width * 0.34f, size.height * 0.49f), size.minDimension * 0.035f, StrokeCap.Round)
}

private fun DrawScope.drawGearGlyph(color: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = size.minDimension * 0.42f
    val inner = outer * 0.68f
    repeat(8) { index ->
        val angle = (index / 8f) * (PI * 2).toFloat()
        val radial = Offset(cos(angle) * outer, sin(angle) * outer)
        val radialInner = Offset(cos(angle) * inner, sin(angle) * inner)
        drawLine(color, center + radialInner, center + radial, size.minDimension * 0.13f, StrokeCap.Square)
    }
    drawCircle(color, inner, center, style = Stroke(size.minDimension * 0.12f))
    drawCircle(Color.Black.copy(alpha = 0.62f), inner * 0.36f, center)
    drawCircle(color.copy(alpha = 0.58f), inner * 0.21f, center)
}

private fun DrawScope.drawSlidersGlyph(color: Color) {
    val xs = listOf(0.24f, 0.50f, 0.76f)
    val knobs = listOf(0.36f, 0.67f, 0.43f)
    xs.forEachIndexed { index, fraction ->
        val x = size.width * fraction
        drawLine(color.copy(alpha = 0.88f), Offset(x, size.height * 0.10f), Offset(x, size.height * 0.90f), size.minDimension * 0.055f, StrokeCap.Round)
        val y = size.height * knobs[index]
        drawCircle(Color.Black.copy(alpha = 0.70f), size.minDimension * 0.14f, Offset(x, y))
        drawCircle(color, size.minDimension * 0.10f, Offset(x, y))
        drawCircle(Color.White.copy(alpha = 0.32f), size.minDimension * 0.028f, Offset(x - size.minDimension * 0.025f, y - size.minDimension * 0.025f))
    }
}

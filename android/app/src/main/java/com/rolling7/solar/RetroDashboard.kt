package com.rolling7.solar

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val RETRO_DEAD = 50.0

internal enum class RetroTab(val label: String) {
    DASHBOARD("TABLOU"),
    ENERGY("ENERGIE"),
    SYSTEM("SISTEM"),
    SETTINGS("SETARI")
}

@Composable
internal fun RetroDashboard(
    data: SolarData?,
    alarmThresholdW: Int,
    selectedTab: RetroTab,
    onTabSelected: (RetroTab) -> Unit,
    onEnergyFieldClick: (String) -> Unit,
    energyContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(
                if (selectedTab == RetroTab.DASHBOARD) {
                    R.drawable.retro_dashboard_background_artwork
                } else {
                    R.drawable.retro_page_background_artwork
                }
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    RetroTab.DASHBOARD -> RetroOverviewPage(
                        data = data,
                        alarmThresholdW = alarmThresholdW,
                        onEnergyFieldClick = onEnergyFieldClick
                    )
                    RetroTab.ENERGY -> energyContent()
                    RetroTab.SYSTEM -> RetroSystemPage(
                        data = data,
                        onEnergyFieldClick = onEnergyFieldClick
                    )
                    RetroTab.SETTINGS -> settingsContent()
                }
            }
            RetroBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }
    }
}

/**
 * TABLOU pastreaza exact ierarhia din referinta: instrumentul ACUM, afisajul PV si fluxul.
 * Nu exista header separat, carduri secundare sau footer care sa concureze cu datele live.
 */
@Composable
private fun RetroOverviewPage(
    data: SolarData?,
    alarmThresholdW: Int,
    onEnergyFieldClick: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 7.dp, top = 0.dp, end = 7.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.Top
    ) {
        RetroLivePanel(
            data = data,
            alarmThresholdW = alarmThresholdW,
            onHouseHistoryClick = { onEnergyFieldClick("output_power") },
            onPvHistoryClick = { onEnergyFieldClick("pv_power") },
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
        )
        RetroFlowPanel(
            data = data,
            onEnergyFieldClick = onEnergyFieldClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                // Resursele au fiecare o mica margine transparenta. Suprapunerea
                // vizuala le apropie fara sa taie umbrele fotografice ale placilor.
                .offset(y = (-14).dp)
        )
    }
}

@Composable
private fun RetroSystemPage(data: SolarData?, onEnergyFieldClick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RetroPageHeader(
            title = "SISTEM",
            subtitle = if (data == null) "TELEMETRIE INDISPONIBILA" else "TELEMETRIE ACTIVA",
            statusColor = if (data == null) RetroRed else RetroSage
        )
        RetroInverterStatusPanel(data)
        RetroSystemPanel(
            data = data,
            onHistoryFieldClick = onEnergyFieldClick,
            modifier = Modifier.weight(1f)
        )
        RetroReadOnlyFooter()
    }
}

@Composable
internal fun RetroPageHeader(
    title: String,
    subtitle: String,
    statusColor: Color = RetroSage,
    modifier: Modifier = Modifier
) {
    RetroPanelSurface(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = RetroText,
                    fontFamily = RetroMono,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    color = statusColor,
                    fontFamily = RetroMono,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RetroLed(
                color = statusColor,
                modifier = Modifier.size(24.dp),
                active = statusColor != RetroOlive
            )
        }
    }
}

@Composable
private fun RetroBottomNavigation(
    selectedTab: RetroTab,
    onTabSelected: (RetroTab) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 7.dp, end = 7.dp, bottom = 6.dp)
            .aspectRatio(1024f / 177f)
    ) {
        Image(
            painter = painterResource(R.drawable.retro_bottom_navigation_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = maxWidth * 0.035f, vertical = maxHeight * 0.09f)
        ) {
            RetroTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = "Tab ${tab.label}${if (selected) ", selectat" else ""}"
                        }
                        .clickable { onTabSelected(tab) }
                ) {
                    if (selected) {
                        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
                            val glowCenter = Offset(size.width / 2f, size.height * 0.88f)
                            drawCircle(RetroYellow.copy(alpha = 0.035f), size.width * 0.52f, glowCenter)
                            drawCircle(RetroYellow.copy(alpha = 0.075f), size.width * 0.32f, glowCenter)
                            drawLine(
                                color = RetroYellow.copy(alpha = 0.22f),
                                start = Offset(size.width * 0.22f, size.height * 0.87f),
                                end = Offset(size.width * 0.78f, size.height * 0.87f),
                                strokeWidth = 7.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = RetroYellow,
                                start = Offset(size.width * 0.26f, size.height * 0.87f),
                                end = Offset(size.width * 0.74f, size.height * 0.87f),
                                strokeWidth = 1.4.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroLivePanel(
    data: SolarData?,
    alarmThresholdW: Int,
    onHouseHistoryClick: () -> Unit,
    onPvHistoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceColor = sourceRetroColor(data)
    val target = (data?.house ?: 0.0).toFloat().coerceIn(0f, 7_000f)
    val animatedValue by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 850),
        label = "ac peste cadranul fotografic"
    )
    val loadColor = retroLoadColor(data?.house ?: 0.0, alarmThresholdW)

    BoxWithConstraints(
        modifier = modifier
            .semantics {
                contentDescription = "Consum casa ${retroWhole(data?.house)} W. Deschide graficul Energie."
            }
            .clickable(onClick = onHouseHistoryClick)
    ) {
        val scale = maxWidth / 1_448f
        Image(
            painter = painterResource(R.drawable.retro_dashboard_live_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Text(
            text = "ACUM",
            modifier = Modifier.offset(x = scale * 104f, y = scale * 60f),
            color = RetroSage,
            fontFamily = RetroMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RetroLed(sourceColor, Modifier.size(16.dp), active = data != null)
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (data == null) "ASTEPT DATE" else "CASA DIN ${sourceRetroLabel(data)}",
                color = sourceColor,
                fontFamily = RetroMono,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.35.sp,
                maxLines = 1
            )
        }

        RetroVfdDisplay(
            value = retroWhole(data?.house),
            unit = "W",
            color = loadColor,
            modifier = Modifier
                .offset(x = scale * 514f, y = scale * 616f)
                .width(scale * 420f)
                .height(scale * 166f),
            embedded = true,
            description = "Consum casa ${retroWhole(data?.house)} W"
        )
        RetroVfdDisplay(
            value = retroWhole(data?.pv1),
            unit = "W",
            color = RetroSage,
            modifier = Modifier
                .offset(x = scale * 241f, y = scale * 916f)
                .width(scale * 203f)
                .height(scale * 65f)
                .clickable(onClick = onPvHistoryClick),
            embedded = true,
            description = "PV1 ${retroWhole(data?.pv1)} W. Deschide graficul Energie."
        )
        RetroVfdDisplay(
            value = retroWhole(data?.pv2),
            unit = "W",
            color = RetroSage,
            modifier = Modifier
                .offset(x = scale * 611f, y = scale * 916f)
                .width(scale * 203f)
                .height(scale * 65f)
                .clickable(onClick = onPvHistoryClick),
            embedded = true,
            description = "PV2 ${retroWhole(data?.pv2)} W. Deschide graficul Energie."
        )
        RetroVfdDisplay(
            value = retroWhole(data?.pv),
            unit = "W",
            color = RetroSage,
            modifier = Modifier
                .offset(x = scale * 893f, y = scale * 849f)
                .width(scale * 385f)
                .height(scale * 126f)
                .clickable(onClick = onPvHistoryClick),
            embedded = true,
            description = "Panouri total ${retroWhole(data?.pv)} W. Deschide graficul Energie."
        )
        RetroArtworkGaugeNeedle(
            animatedValue = animatedValue,
            color = loadColor,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun RetroArtworkGaugeNeedle(
    animatedValue: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val center = Offset(size.width * 0.50f, size.height * 0.511f)
        val fraction = (animatedValue / 7_000f).coerceIn(0f, 1f)
        val angle = (155f + 230f * fraction) * PI.toFloat() / 180f
        val direction = Offset(cos(angle), sin(angle))
        val perpendicular = Offset(-direction.y, direction.x)
        val length = size.width * 0.275f
        val tip = center + direction * length
        val tail = center - direction * (size.width * 0.018f)
        val halfWidth = size.width * 0.0044f

        val shadow = Path().apply {
            moveTo(tip.x + 3.dp.toPx(), tip.y + 4.dp.toPx())
            lineTo(tail.x + perpendicular.x * halfWidth + 3.dp.toPx(), tail.y + perpendicular.y * halfWidth + 4.dp.toPx())
            lineTo(tail.x - perpendicular.x * halfWidth + 3.dp.toPx(), tail.y - perpendicular.y * halfWidth + 4.dp.toPx())
            close()
        }
        drawPath(shadow, Color.Black.copy(alpha = 0.72f))

        val needle = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tail.x + perpendicular.x * halfWidth, tail.y + perpendicular.y * halfWidth)
            lineTo(tail.x - perpendicular.x * halfWidth, tail.y - perpendicular.y * halfWidth)
            close()
        }
        drawPath(
            path = needle,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFE7DEAB), color, Color(0xFF53623D)),
                start = tip,
                end = tail
            )
        )
        drawLine(
            color = Color.White.copy(alpha = 0.42f),
            start = tip + perpendicular * 0.5.dp.toPx(),
            end = center + perpendicular * 0.5.dp.toPx(),
            strokeWidth = 0.7.dp.toPx(),
            cap = StrokeCap.Round
        )
        val pivotRadius = size.width * 0.016f
        drawCircle(Color.Black.copy(alpha = 0.72f), pivotRadius * 1.30f, center + Offset(2.dp.toPx(), 3.dp.toPx()))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(RetroBrassLight, RetroBrass, RetroBrassDark, Color.Black),
                center = center - Offset(pivotRadius * 0.25f, pivotRadius * 0.28f),
                radius = pivotRadius * 1.15f
            ),
            radius = pivotRadius,
            center = center
        )
        drawCircle(Color.Black.copy(alpha = 0.82f), pivotRadius * 0.25f, center)
        drawCircle(Color.White.copy(alpha = 0.24f), pivotRadius * 0.09f, center - Offset(pivotRadius * 0.07f, pivotRadius * 0.09f))
    }
}

@Composable
private fun RetroPvInstrument(
    data: SolarData?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    RetroMetalButton(
        modifier = modifier,
        selected = false,
        accent = RetroSage,
        description = "Panouri acum ${retroWhole(data?.pv)} W. Deschide graficul Energie.",
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 17.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "PANOURI ACUM",
                    color = RetroText,
                    fontFamily = RetroMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    data?.let { "PV1  ${it.pv1.roundToInt()} W    PV2  ${it.pv2.roundToInt()} W" } ?: "PV1  — W    PV2  — W",
                    color = RetroSage,
                    fontFamily = RetroMono,
                    fontSize = 8.sp,
                    letterSpacing = 0.25.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RetroVfdDisplay(
                value = retroWhole(data?.pv),
                unit = "W",
                color = RetroSage,
                modifier = Modifier.width(100.dp).fillMaxHeight(),
                description = "Putere panouri ${retroWhole(data?.pv)} W"
            )
        }
    }
}

@Composable
private fun AnalogHouseGauge(
    valueW: Double?,
    alarmThresholdW: Int,
    modifier: Modifier = Modifier
) {
    val target = (valueW ?: 0.0).toFloat().coerceIn(0f, 7_000f)
    val loadColor = retroLoadColor(valueW ?: 0.0, alarmThresholdW)
    val animatedValue by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 850),
        label = "ac metalic cadran consum"
    )

    RetroInsetSurface(modifier = modifier, shape = RoundedCornerShape(13.dp)) {
        RetroGaugePaperTexture(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp)
        )
        GaugeFace(
            animatedValue = animatedValue,
            alarmThresholdW = alarmThresholdW,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            "CONSUM CASA",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            color = RetroText,
            fontFamily = RetroMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        RetroVfdDisplay(
            value = retroWhole(valueW),
            unit = "W",
            color = loadColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .width(154.dp)
                .height(57.dp),
            minDigits = 1,
            description = "Consum casa ${retroWhole(valueW)} W"
        )
        GaugeNeedle(
            animatedValue = animatedValue,
            color = loadColor,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun GaugeFace(
    animatedValue: Float,
    alarmThresholdW: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
        val center = Offset(size.width / 2f, size.height * 0.59f)
        val radius = min(size.width * 0.43f, size.height * 0.54f)
        val startAngle = 155f
        val sweep = 230f
        val thresholdFraction = (alarmThresholdW / 7_000f).coerceIn(0f, 1f)
        val arcTopLeft = center - Offset(radius, radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        drawArc(
            RetroBrassDark,
            startAngle,
            sweep,
            false,
            arcTopLeft,
            arcSize,
            style = Stroke(8.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            RetroBrassLight.copy(alpha = 0.74f),
            startAngle,
            sweep,
            false,
            arcTopLeft,
            arcSize,
            style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            RetroRed.copy(alpha = 0.78f),
            startAngle + sweep * thresholdFraction,
            sweep * (1f - thresholdFraction),
            false,
            arcTopLeft,
            arcSize,
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        fun point(angleDegrees: Float, distance: Float): Offset {
            val radians = angleDegrees * PI.toFloat() / 180f
            return Offset(
                center.x + cos(radians) * distance,
                center.y + sin(radians) * distance
            )
        }

        val labelTypeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RetroText.copy(alpha = 0.82f).toArgb()
            textSize = min(13.sp.toPx(), radius * 0.14f)
            textAlign = Paint.Align.CENTER
            typeface = labelTypeface
        }
        val labelEngravingPaint = Paint(labelPaint).apply {
            color = Color.Black.copy(alpha = 0.88f).toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1.15.dp.toPx()
        }
        val labelEdgePaint = Paint(labelPaint).apply {
            color = RetroBrassLight.copy(alpha = 0.30f).toArgb()
        }
        for (tick in 0..70) {
            val tickFraction = tick / 70f
            val angle = startAngle + sweep * tickFraction
            val major = tick % 10 == 0
            val medium = tick % 5 == 0
            val tickLength = when {
                major -> 15.dp.toPx()
                medium -> 10.dp.toPx()
                else -> 6.dp.toPx()
            }
            val outer = radius - 5.dp.toPx()
            val inner = outer - tickLength
            val color = if (tickFraction >= thresholdFraction) RetroRed else RetroText
            val wear = 0.60f + ((sin((tick + 1) * 2.17f) + 1f) * 0.16f)
            drawLine(
                color = color.copy(alpha = wear * if (major) 1f else 0.80f),
                start = point(angle, inner),
                end = point(angle, outer),
                strokeWidth = when {
                    major -> 2.8.dp.toPx()
                    medium -> 1.7.dp.toPx()
                    else -> 0.85.dp.toPx()
                },
                cap = StrokeCap.Square
            )
            if (major) {
                val labelPoint = point(angle, radius - 31.dp.toPx())
                val baseline = labelPoint.y + labelPaint.textSize * 0.33f
                val label = (tick / 10).toString()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    labelPoint.x + 0.7.dp.toPx(),
                    baseline + 0.9.dp.toPx(),
                    labelEngravingPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    labelPoint.x - 0.35.dp.toPx(),
                    baseline - 0.45.dp.toPx(),
                    labelEdgePaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    labelPoint.x,
                    baseline,
                    labelPaint
                )
            }
        }

        // Puncte fine de calibrare pe interiorul scalei.
        repeat(36) { index ->
            val angle = startAngle + sweep * index / 35f
            drawCircle(
                color = RetroOlive.copy(alpha = 0.34f),
                radius = 0.75.dp.toPx(),
                center = point(angle, radius - 42.dp.toPx())
            )
        }
    }
}

@Composable
private fun GaugeNeedle(
    animatedValue: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
        val center = Offset(size.width / 2f, size.height * 0.59f)
        val radius = min(size.width * 0.43f, size.height * 0.54f)
        val fraction = (animatedValue / 7_000f).coerceIn(0f, 1f)
        val angle = (155f + 230f * fraction) * PI.toFloat() / 180f
        val direction = Offset(cos(angle), sin(angle))
        val perpendicular = Offset(-direction.y, direction.x)
        val tip = center + direction * (radius - 31.dp.toPx())
        val tail = center - direction * 13.dp.toPx()
        val halfWidth = 3.1.dp.toPx()

        val shadow = Path().apply {
            moveTo((tail + Offset(2.dp.toPx(), 3.dp.toPx())).x, (tail + Offset(2.dp.toPx(), 3.dp.toPx())).y)
            lineTo((center + perpendicular * halfWidth + Offset(2.dp.toPx(), 3.dp.toPx())).x, (center + perpendicular * halfWidth + Offset(2.dp.toPx(), 3.dp.toPx())).y)
            lineTo((tip + Offset(2.dp.toPx(), 3.dp.toPx())).x, (tip + Offset(2.dp.toPx(), 3.dp.toPx())).y)
            lineTo((center - perpendicular * halfWidth + Offset(2.dp.toPx(), 3.dp.toPx())).x, (center - perpendicular * halfWidth + Offset(2.dp.toPx(), 3.dp.toPx())).y)
            close()
        }
        drawPath(shadow, Color.Black.copy(alpha = 0.72f))

        val needle = Path().apply {
            moveTo(tail.x, tail.y)
            lineTo((center + perpendicular * halfWidth).x, (center + perpendicular * halfWidth).y)
            lineTo(tip.x, tip.y)
            lineTo((center - perpendicular * halfWidth).x, (center - perpendicular * halfWidth).y)
            close()
        }
        drawPath(
            needle,
            brush = Brush.linearGradient(
                listOf(RetroBrassDark, color, Color(0xFFE7F4AF)),
                start = tail,
                end = tip
            )
        )
        drawLine(Color.White.copy(alpha = 0.48f), center, tip, 0.8.dp.toPx(), StrokeCap.Round)

        drawCircle(Color.Black.copy(alpha = 0.62f), 11.dp.toPx(), center + Offset(1.5.dp.toPx(), 2.dp.toPx()))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(RetroBrassLight, RetroBrass, RetroBrassDark, Color.Black),
                center = center - Offset(2.dp.toPx(), 2.dp.toPx()),
                radius = 10.dp.toPx()
            ),
            radius = 9.dp.toPx(),
            center = center
        )
        drawCircle(Color.Black.copy(alpha = 0.75f), 3.dp.toPx(), center)
        drawCircle(Color.White.copy(alpha = 0.34f), 1.dp.toPx(), center - Offset(1.dp.toPx(), 1.dp.toPx()))
    }
}

@Composable
private fun RetroFlowPanel(
    data: SolarData?,
    onEnergyFieldClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pv = data?.pv ?: 0.0
    val battery = data?.batteryDisplay ?: 0.0
    val charging = (data?.batteryCharge ?: 0.0) > RETRO_DEAD || battery > RETRO_DEAD
    val discharging = (data?.batterySupport ?: 0.0) > RETRO_DEAD || battery < -RETRO_DEAD
    val grid = (data?.gridImport ?: 0.0) + (data?.gridCharge ?: 0.0)
    val phase by rememberInfiniteTransition(label = "flux retro industrial").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "leduri flux retro"
    )

    BoxWithConstraints(modifier = modifier) {
        val scale = maxWidth / 1_448f
        Image(
            painter = painterResource(R.drawable.retro_dashboard_flow_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Canvas(Modifier.fillMaxSize()) {
            fun movingLeds(start: Offset, end: Offset, active: Boolean, color: Color) {
                if (!active) return
                val vector = end - start
                val length = sqrt(vector.x * vector.x + vector.y * vector.y).coerceAtLeast(1f)
                val perpendicular = Offset(-vector.y / length, vector.x / length)
                repeat(3) { index ->
                    val progress = (phase + index / 3f) % 1f
                    val jitter = sin((index + 1) * 4.13f + phase * 6.7f) * 1.05.dp.toPx()
                    val point = start + vector * progress + perpendicular * jitter
                    val irregularity = 0.84f + 0.16f * sin((index + 2) * 2.47f + phase * 5.1f)
                    drawCircle(color.copy(alpha = 0.055f), 8.dp.toPx() * irregularity, point)
                    drawCircle(color.copy(alpha = 0.16f), 4.5.dp.toPx() * irregularity, point)
                    drawCircle(color.copy(alpha = 0.92f), 2.05.dp.toPx() * irregularity, point)
                    drawCircle(
                        Color.White.copy(alpha = 0.52f),
                        0.58.dp.toPx(),
                        point - Offset(0.45.dp.toPx(), 0.50.dp.toPx())
                    )
                }
            }

            val solarJoint = Offset(size.width * 0.452f, size.height * 0.361f)
            val batteryJoint = Offset(size.width * 0.232f, size.height * 0.671f)
            val houseLeft = Offset(size.width * 0.447f, size.height * 0.674f)
            val houseRight = Offset(size.width * 0.619f, size.height * 0.674f)
            val gridJoint = Offset(size.width * 0.775f, size.height * 0.674f)

            movingLeds(solarJoint, batteryJoint, pv > RETRO_DEAD, RetroSage)
            when {
                discharging -> movingLeds(batteryJoint, houseLeft, active = true, color = RetroYellow)
                pv > RETRO_DEAD -> movingLeds(batteryJoint, houseLeft, active = true, color = RetroSage)
            }
            if (charging && pv <= RETRO_DEAD) {
                movingLeds(houseLeft, batteryJoint, active = true, color = RetroRed)
            }
            movingLeds(gridJoint, houseRight, grid > RETRO_DEAD, RetroRed)
        }

        RetroArtworkFlowValue(
            value = "${retroWhole(data?.pv)} W",
            color = RetroSage,
            description = "Panouri ${retroWhole(data?.pv)} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick("pv_power") },
            modifier = Modifier
                .offset(x = scale * 596f, y = scale * 470f)
                .width(scale * 270f)
        )
        RetroArtworkFlowValue(
            value = "${retroSigned(data?.batteryDisplay)} W",
            color = RetroYellow,
            description = "Baterie ${retroSigned(data?.batteryDisplay)} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick("battery_voltage") },
            modifier = Modifier
                .offset(x = scale * 70f, y = scale * 856f)
                .width(scale * 300f)
        )
        RetroArtworkFlowValue(
            value = "${retroWhole(data?.house)} W",
            color = RetroHouseBlue,
            description = "Casa ${retroWhole(data?.house)} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick("output_power") },
            modifier = Modifier
                .offset(x = scale * 580f, y = scale * 856f)
                .width(scale * 290f)
        )
        RetroArtworkFlowValue(
            value = "${retroWhole(if (data == null) null else grid)} W",
            color = RetroRed,
            description = "Retea ${retroWhole(if (data == null) null else grid)} W",
            modifier = Modifier
                .offset(x = scale * 1_075f, y = scale * 856f)
                .width(scale * 285f)
        )
    }
}

@Composable
private fun RetroArtworkFlowValue(
    value: String,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Text(
        text = value,
        modifier = modifier
            .then(clickModifier)
            .semantics { contentDescription = description },
        color = color,
        fontFamily = RetroMono,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

@Composable
private fun RetroFlowNode(
    modifier: Modifier,
    asset: RetroFlowAsset,
    value: String,
    color: Color,
    description: String,
    onClick: (() -> Unit)? = null
) {
    val click = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Column(
        modifier
            .then(click)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RetroIsometricAsset(asset = asset, modifier = Modifier.size(62.dp))
        Text(
            text = value,
            color = color,
            fontFamily = RetroMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            maxLines = 1
        )
    }
}

@Composable
internal fun RetroDailyPanel(
    data: SolarData?,
    onHistoryFieldClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    RetroPanelSurface(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        fillContent = true
    ) {
        RetroSectionLabel("ENERGIE ASTAZI")
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroDailyCell(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "PRODUS",
                value = retroDecimal(data?.energyPvToday),
                total = data?.let { "TOTAL ${it.energyPvTotal.roundToInt()} kWh" } ?: "ASTEPT DATE",
                color = RetroSage,
                onClick = { onHistoryFieldClick("energy_pv_today") }
            )
            RetroDailyCell(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "CONSUM",
                value = retroDecimal(data?.energyLoadToday),
                total = data?.let { "TOTAL ${it.energyLoadTotal.roundToInt()} kWh" } ?: "ASTEPT DATE",
                color = RetroHouseBlue,
                onClick = { onHistoryFieldClick("energy_load_today") }
            )
        }
    }
}

@Composable
private fun RetroDailyCell(
    modifier: Modifier,
    label: String,
    value: String,
    total: String,
    color: Color,
    onClick: () -> Unit
) {
    RetroMetalButton(
        modifier = modifier,
        selected = false,
        accent = color,
        description = "$label $value kWh. Deschide graficul.",
        onClick = onClick
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                color = RetroText,
                fontFamily = RetroMono,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )
            RetroVfdDisplay(
                value = value,
                unit = "kWh",
                color = color,
                modifier = Modifier.fillMaxWidth().height(30.dp),
                description = "$label $value kilowatt-ora"
            )
            Text(
                total,
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 6.sp,
                letterSpacing = 0.25.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RetroInverterStatusPanel(data: SolarData?) {
    RetroPanelSurface {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RetroLed(
                color = if (data == null) RetroRed else RetroSage,
                modifier = Modifier.size(42.dp),
                active = data != null
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (data == null) "FARA DATE" else "INVERTOR CONECTAT",
                    color = if (data == null) RetroRed else RetroSage,
                    fontFamily = RetroMono,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "GROWATT SPF 6000 ES PLUS",
                    color = RetroMuted,
                    fontFamily = RetroMono,
                    fontSize = 8.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    data?.let { "COD ${it.status.roundToInt()}" } ?: "COD —",
                    color = RetroYellow,
                    fontFamily = RetroMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    retroTimestamp(data?.timestamp),
                    color = RetroMuted,
                    fontFamily = RetroMono,
                    fontSize = 8.sp
                )
            }
        }
    }
}

@Composable
private fun RetroSystemPanel(
    data: SolarData?,
    onHistoryFieldClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    RetroPanelSurface(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        fillContent = true
    ) {
        RetroSectionLabel("INSTRUMENTE SISTEM")
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroSystemInstrument(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "BATERIE",
                value = data?.let { String.format(Locale.US, "%.2f", it.batteryVoltage) } ?: "—",
                unit = "V",
                supporting = data?.let { "SOC ${it.batterySoc.roundToInt()}% · ${retroSigned(it.batteryDisplay)} W" } ?: "ASTEPT DATE",
                color = RetroYellow,
                onClick = { onHistoryFieldClick("battery_voltage") }
            )
            RetroSystemInstrument(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "RETEA",
                value = data?.let { String.format(Locale.US, "%.1f", it.gridVoltage) } ?: "—",
                unit = "V",
                supporting = data?.let { "IMPORT ${(it.gridImport + it.gridCharge).roundToInt()} W" } ?: "ASTEPT DATE",
                color = RetroRed
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroSystemInstrument(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "TEMPERATURA",
                value = data?.let { String.format(Locale.US, "%.1f", it.inverterTemp) } ?: "—",
                unit = "°C",
                supporting = "INVERTOR",
                color = RetroSage
            )
            RetroSystemInstrument(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "PIERDERI",
                value = data?.let { it.inverterLoss.roundToInt().toString() } ?: "—",
                unit = "W",
                supporting = "CONSUM PROPRIU",
                color = RetroOlive
            )
        }
    }
}

@Composable
private fun RetroSystemInstrument(
    modifier: Modifier,
    label: String,
    value: String,
    unit: String,
    supporting: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    val click = if (onClick == null) ({}) else onClick
    RetroMetalButton(
        modifier = modifier,
        selected = false,
        accent = color,
        description = "$label $value $unit",
        onClick = click
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                label,
                color = RetroText,
                fontFamily = RetroMono,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            RetroVfdDisplay(
                value = value,
                unit = unit,
                color = color,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                description = "$label $value $unit"
            )
            Text(
                supporting,
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RetroReadOnlyFooter() {
    Text(
        "GROWATT SPF 6000 ES PLUS  ·  READ ONLY",
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = RetroMuted,
        fontFamily = RetroMono,
        fontSize = 8.sp,
        letterSpacing = 0.9.sp,
        textAlign = TextAlign.Center
    )
}

private fun retroTimestamp(value: String?): String {
    if (value.isNullOrBlank()) return "READ ONLY"
    return runCatching {
        OffsetDateTime.parse(value)
            .atZoneSameInstant(ZoneId.of("Europe/Bucharest"))
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }.getOrDefault("SYNC API")
}

private fun retroWhole(value: Double?): String = value?.roundToInt()?.toString() ?: "—"

private fun retroSigned(value: Double?): String {
    val rounded = value?.roundToInt() ?: return "—"
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun retroDecimal(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

private fun sourceRetroLabel(data: SolarData): String = when (data.houseSource.roundToInt()) {
    1 -> "SOLAR"
    2 -> "BATERIE"
    3 -> "RETEA"
    else -> when {
        data.gridImport + data.gridCharge > RETRO_DEAD -> "RETEA"
        data.batterySupport > RETRO_DEAD || data.batteryDisplay < -RETRO_DEAD -> "BATERIE"
        data.pv > RETRO_DEAD -> "SOLAR"
        else -> "STANDBY"
    }
}

private fun sourceRetroColor(data: SolarData?): Color = when (data?.let(::sourceRetroLabel)) {
    "SOLAR" -> RetroSage
    "BATERIE" -> RetroYellow
    "RETEA" -> RetroRed
    else -> RetroOlive
}

private fun retroLoadColor(valueW: Double, alarmThresholdW: Int): Color = when {
    valueW >= alarmThresholdW -> RetroRed
    valueW >= alarmThresholdW * 0.8 -> RetroYellow
    else -> RetroSage
}

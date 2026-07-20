package com.rolling7.solar

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val RetroSage = Color(0xFFACCC78)
private val RetroOlive = Color(0xFF81795A)
private val RetroYellow = Color(0xFFF1E169)
private val RetroBackground = Color(0xFF14150F)
private val RetroPanel = Color(0xFF202117)
private val RetroPanelRaised = Color(0xFF29291C)
private val RetroText = Color(0xFFE8E3CA)
private val RetroMuted = Color(0xFFA9A184)
private val RetroLine = Color(0xFF5E5A43)
private const val RETRO_DEAD = 50.0

@Composable
fun RetroDashboard(
    data: SolarData?,
    alarmThresholdW: Int,
    onHistoryClick: () -> Unit,
    onHistoryFieldClick: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(RetroBackground)
    ) {
        RetroBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RetroHeader(
                onHistoryClick = onHistoryClick,
                onSettingsClick = onSettingsClick
            )
            RetroLivePanel(
                data = data,
                alarmThresholdW = alarmThresholdW,
                onHouseHistoryClick = { onHistoryFieldClick("output_power") },
                onPvHistoryClick = { onHistoryFieldClick("pv_power") }
            )
            RetroFlowPanel(data = data)
            RetroDailyPanel(data = data, onHistoryFieldClick = onHistoryFieldClick)
            RetroSystemPanel(data = data, onHistoryFieldClick = onHistoryFieldClick)
            Text(
                "GROWATT SPF 6000 ES PLUS  ·  READ ONLY",
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = RetroOlive,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RetroBackdrop(modifier: Modifier) {
    Canvas(modifier) {
        val step = 24.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = RetroOlive.copy(alpha = 0.025f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            y += step
        }
    }
}

@Composable
private fun RetroHeader(onHistoryClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "SOLAR MONITOR",
                color = RetroYellow,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(RetroSage))
                Spacer(Modifier.width(7.dp))
                Text(
                    "SISTEM IN FUNCTIUNE",
                    color = RetroSage,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
        RetroHeaderButton("ISTORIC", onHistoryClick)
        Surface(
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = "Setari" }
                .clickable(onClick = onSettingsClick),
            shape = CircleShape,
            color = RetroPanel,
            border = BorderStroke(1.dp, RetroOlive.copy(alpha = 0.65f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("⚙", color = RetroYellow, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun RetroHeaderButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = RetroPanel,
        border = BorderStroke(1.dp, RetroOlive.copy(alpha = 0.75f))
    ) {
        Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = RetroYellow,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun RetroLivePanel(
    data: SolarData?,
    alarmThresholdW: Int,
    onHouseHistoryClick: () -> Unit,
    onPvHistoryClick: () -> Unit
) {
    RetroPanelSurface {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RetroSectionLabel("ACUM")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(sourceRetroColor(data)))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (data == null) "ASTEPT DATE" else "CASA DIN ${sourceRetroLabel(data)}",
                    color = sourceRetroColor(data),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        AnalogHouseGauge(
            valueW = data?.house,
            alarmThresholdW = alarmThresholdW,
            modifier = Modifier
                .fillMaxWidth()
                .height(205.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Consum casa ${retroWhole(data?.house)} W. Deschide istoricul."
                }
                .clickable(onClick = onHouseHistoryClick)
        )
        RetroDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onPvHistoryClick)
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "PANOURI ACUM",
                    color = RetroMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                Text(
                    data?.let { "PV1 ${it.pv1.roundToInt()} W  ·  PV2 ${it.pv2.roundToInt()} W" } ?: "ASTEPT DATE",
                    color = RetroOlive,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SevenSegmentValue(
                value = retroWhole(data?.pv),
                color = RetroSage,
                modifier = Modifier.width(88.dp).height(38.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("W", color = RetroMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AnalogHouseGauge(valueW: Double?, alarmThresholdW: Int, modifier: Modifier = Modifier) {
    val target = (valueW ?: 0.0).toFloat().coerceIn(0f, 7_000f)
    val animatedValue by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 700),
        label = "ac cadran consum"
    )
    Box(modifier.background(RetroBackground.copy(alpha = 0.46f))) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val center = Offset(size.width / 2f, size.height * 0.72f)
            val radius = min(size.width * 0.39f, size.height * 0.63f)
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            val startAngle = 150f
            val sweep = 240f
            val fraction = (animatedValue / 7_000f).coerceIn(0f, 1f)
            val thresholdFraction = (alarmThresholdW / 7_000f).coerceIn(0f, 1f)

            drawArc(
                color = RetroOlive.copy(alpha = 0.34f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = RetroYellow.copy(alpha = 0.22f),
                startAngle = startAngle + sweep * thresholdFraction,
                sweepAngle = sweep * (1f - thresholdFraction),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = if (fraction >= thresholdFraction) RetroYellow else RetroSage,
                startAngle = startAngle,
                sweepAngle = sweep * fraction,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )

            fun point(angleDegrees: Float, distance: Float): Offset {
                val radians = angleDegrees * PI.toFloat() / 180f
                return Offset(
                    x = center.x + cos(radians) * distance,
                    y = center.y + sin(radians) * distance
                )
            }

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = RetroMuted.toArgb()
                textSize = 9.sp.toPx()
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
            }
            for (tick in 0..35) {
                val tickFraction = tick / 35f
                val angle = startAngle + sweep * tickFraction
                val major = tick % 5 == 0
                val from = point(angle, radius - if (major) 15.dp.toPx() else 10.dp.toPx())
                val to = point(angle, radius + 1.dp.toPx())
                drawLine(
                    color = if (tickFraction >= thresholdFraction) RetroYellow else RetroSage,
                    start = from,
                    end = to,
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
                if (major) {
                    val labelPoint = point(angle, radius - 27.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText(
                        (tick / 5).toString(),
                        labelPoint.x,
                        labelPoint.y + 3.dp.toPx(),
                        labelPaint
                    )
                }
            }

            val needleAngle = startAngle + sweep * fraction
            val needleEnd = point(needleAngle, radius - 31.dp.toPx())
            drawLine(
                color = RetroYellow,
                start = center,
                end = needleEnd,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(RetroBackground, radius = 9.dp.toPx(), center = center)
            drawCircle(RetroYellow, radius = 5.dp.toPx(), center = center)
            drawCircle(RetroBackground, radius = 2.dp.toPx(), center = center)
        }

        Text(
            "CONSUM CASA",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            color = RetroMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            SevenSegmentValue(
                value = retroWhole(valueW),
                color = if ((valueW ?: 0.0) >= alarmThresholdW) RetroYellow else RetroSage,
                modifier = Modifier.width(112.dp).height(46.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text("W", color = RetroMuted, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
    }
}

@Composable
private fun RetroFlowPanel(data: SolarData?) {
    val pv = data?.pv ?: 0.0
    val battery = data?.batteryDisplay ?: 0.0
    val charging = (data?.batteryCharge ?: 0.0) > RETRO_DEAD || battery > RETRO_DEAD
    val discharging = (data?.batterySupport ?: 0.0) > RETRO_DEAD || battery < -RETRO_DEAD
    val grid = (data?.gridImport ?: 0.0) + (data?.gridCharge ?: 0.0)
    val phase by rememberInfiniteTransition(label = "flux retro").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particule retro"
    )

    RetroPanelSurface {
        RetroSectionLabel("FLUX ENERGETIC")
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(218.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val pvPoint = Offset(size.width / 2f, 23.dp.toPx())
                val batteryPoint = Offset(43.dp.toPx(), size.height - 64.dp.toPx())
                val housePoint = Offset(size.width / 2f, size.height - 64.dp.toPx())
                val gridPoint = Offset(size.width - 43.dp.toPx(), size.height - 64.dp.toPx())

                fun connection(start: Offset, end: Offset, active: Boolean, color: Color) {
                    drawLine(
                        RetroLine.copy(alpha = 0.72f),
                        start,
                        end,
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    if (!active) return
                    repeat(3) { index ->
                        val progress = (phase + index / 3f) % 1f
                        val point = Offset(
                            start.x + (end.x - start.x) * progress,
                            start.y + (end.y - start.y) * progress
                        )
                        drawCircle(color.copy(alpha = 0.18f), 6.dp.toPx(), point)
                        drawCircle(color, 2.5.dp.toPx(), point)
                    }
                }

                connection(pvPoint, housePoint, pv > RETRO_DEAD, RetroSage)
                connection(pvPoint, batteryPoint, charging, RetroSage)
                connection(batteryPoint, housePoint, discharging, RetroYellow)
                connection(gridPoint, housePoint, grid > RETRO_DEAD, RetroYellow)
            }

            RetroFlowNode(
                modifier = Modifier.align(Alignment.TopCenter).width(88.dp),
                symbol = "PV",
                label = "PANOURI",
                value = "${retroWhole(data?.pv)} W",
                color = RetroSage
            )
            RetroFlowNode(
                modifier = Modifier.align(Alignment.BottomStart).width(86.dp),
                symbol = "BAT",
                label = "BATERIE",
                value = "${retroSigned(data?.batteryDisplay)} W",
                color = RetroYellow
            )
            RetroFlowNode(
                modifier = Modifier.align(Alignment.BottomCenter).width(86.dp),
                symbol = "CASA",
                label = "CASA",
                value = "${retroWhole(data?.house)} W",
                color = RetroSage
            )
            RetroFlowNode(
                modifier = Modifier.align(Alignment.BottomEnd).width(86.dp),
                symbol = "AC",
                label = "RETEA",
                value = "${retroWhole(if (data == null) null else grid)} W",
                color = RetroOlive
            )
        }
    }
}

@Composable
private fun RetroFlowNode(
    modifier: Modifier,
    symbol: String,
    label: String,
    value: String,
    color: Color
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = RetroPanelRaised,
            border = BorderStroke(1.dp, color.copy(alpha = 0.78f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    symbol,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (symbol.length > 2) 8.sp else 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = RetroMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1)
        Text(
            value,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun RetroDailyPanel(data: SolarData?, onHistoryFieldClick: (String) -> Unit) {
    RetroPanelSurface {
        RetroSectionLabel("ASTAZI")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RetroDailyCell(
                modifier = Modifier.weight(1f),
                label = "PRODUS",
                value = retroDecimal(data?.energyPvToday),
                total = data?.let { "TOTAL ${it.energyPvTotal.roundToInt()} kWh" } ?: "ASTEPT DATE",
                color = RetroSage,
                onClick = { onHistoryFieldClick("energy_pv_today") }
            )
            Box(Modifier.width(1.dp).height(82.dp).background(RetroLine))
            RetroDailyCell(
                modifier = Modifier.weight(1f),
                label = "CONSUM",
                value = retroDecimal(data?.energyLoadToday),
                total = data?.let { "TOTAL ${it.energyLoadTotal.roundToInt()} kWh" } ?: "ASTEPT DATE",
                color = RetroYellow,
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
    Column(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = RetroMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp
        )
        Row(verticalAlignment = Alignment.Bottom) {
            SevenSegmentValue(value, color, Modifier.width(92.dp).height(42.dp))
            Spacer(Modifier.width(4.dp))
            Text("kWh", color = RetroMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
        Text(
            total,
            color = RetroOlive,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RetroSystemPanel(data: SolarData?, onHistoryFieldClick: (String) -> Unit) {
    RetroPanelSurface {
        RetroSectionLabel("SISTEM")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            RetroStatusCell(
                Modifier.weight(1f),
                "BATERIE",
                data?.let { String.format(Locale.US, "%.2f V", it.batteryVoltage) } ?: "—",
                data?.let { "${retroSigned(it.batteryDisplay)} W" } ?: "ASTEPT",
                RetroSage,
                onClick = { onHistoryFieldClick("battery_voltage") }
            )
            RetroStatusDivider()
            RetroStatusCell(
                Modifier.weight(1f),
                "RETEA",
                data?.let { String.format(Locale.US, "%.1f V", it.gridVoltage) } ?: "—",
                data?.let { "IMPORT ${((it.gridImport + it.gridCharge).roundToInt())} W" } ?: "ASTEPT",
                RetroYellow
            )
        }
        RetroDivider()
        Row(Modifier.fillMaxWidth()) {
            RetroStatusCell(
                Modifier.weight(1f),
                "TEMPERATURA",
                data?.let { String.format(Locale.US, "%.1f °C", it.inverterTemp) } ?: "—",
                "INVERTOR",
                RetroSage
            )
            RetroStatusDivider()
            RetroStatusCell(
                Modifier.weight(1f),
                "PIERDERI",
                data?.let { "${it.inverterLoss.roundToInt()} W" } ?: "—",
                "CONSUM PROPRIU",
                RetroOlive
            )
        }
    }
}

@Composable
private fun RetroStatusCell(
    modifier: Modifier,
    label: String,
    value: String,
    supporting: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier
            .then(clickModifier)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = RetroMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                supporting,
                color = RetroOlive,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            value,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun RetroStatusDivider() {
    Box(Modifier.width(1.dp).height(54.dp).background(RetroLine.copy(alpha = 0.7f)))
}

@Composable
private fun RetroPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = RetroPanel,
        border = BorderStroke(1.dp, RetroOlive.copy(alpha = 0.62f)),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), content = content)
    }
}

@Composable
private fun RetroSectionLabel(label: String) {
    Text(
        label,
        color = RetroMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun RetroDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(RetroLine.copy(alpha = 0.62f))
    )
}

@Composable
private fun SevenSegmentValue(
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val spoken = if (value == "—") "fara date" else value
    Canvas(modifier.semantics { contentDescription = spoken }) {
        val characters = value.ifEmpty { "—" }
        val totalUnits = characters.sumOf { character -> if (character == '.') 0.32 else 1.0 }.toFloat()
        val cellWidth = size.width / totalUnits.coerceAtLeast(1f)
        val thickness = min(cellWidth * 0.12f, size.height * 0.095f).coerceAtLeast(1.5f)
        val horizontalInset = thickness * 1.15f
        val verticalInset = thickness * 0.8f
        val upperY = size.height * 0.49f
        val inactive = color.copy(alpha = 0.055f)

        fun segmentsFor(character: Char): Set<Char> = when (character) {
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
            else -> emptySet()
        }

        var x = 0f
        characters.forEach { character ->
            if (character == '.') {
                val dotWidth = cellWidth * 0.32f
                drawCircle(color, radius = thickness * 0.75f, center = Offset(x + dotWidth / 2f, size.height - thickness))
                x += dotWidth
                return@forEach
            }
            val active = segmentsFor(character)
            val left = x + horizontalInset
            val right = x + cellWidth - horizontalInset
            val top = verticalInset
            val middle = upperY
            val bottom = size.height - verticalInset

            fun segment(name: Char, start: Offset, end: Offset) {
                drawLine(
                    color = if (name in active) color else inactive,
                    start = start,
                    end = end,
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
            }

            segment('a', Offset(left, top), Offset(right, top))
            segment('g', Offset(left, middle), Offset(right, middle))
            segment('d', Offset(left, bottom), Offset(right, bottom))
            segment('f', Offset(left, top + thickness), Offset(left, middle - thickness))
            segment('b', Offset(right, top + thickness), Offset(right, middle - thickness))
            segment('e', Offset(left, middle + thickness), Offset(left, bottom - thickness))
            segment('c', Offset(right, middle + thickness), Offset(right, bottom - thickness))
            if (character == '+') {
                drawLine(
                    color = color,
                    start = Offset(left, middle),
                    end = Offset(right, middle),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(x + cellWidth / 2f, middle - cellWidth * 0.24f),
                    end = Offset(x + cellWidth / 2f, middle + cellWidth * 0.24f),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
            }
            x += cellWidth
        }
    }
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
    "RETEA" -> RetroYellow
    else -> RetroOlive
}

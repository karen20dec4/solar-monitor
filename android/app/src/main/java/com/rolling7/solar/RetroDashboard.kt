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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal val RetroSage = Color(0xFFACCC78)
internal val RetroOlive = Color(0xFF81795A)
internal val RetroYellow = Color(0xFFF1E169)
internal val RetroRed = Color(0xFFD66B5D)
internal val RetroBackground = Color(0xFF14150F)
internal val RetroPanel = Color(0xFF202117)
internal val RetroPanelRaised = Color(0xFF29291C)
internal val RetroText = Color(0xFFE8E3CA)
internal val RetroMuted = Color(0xFFA9A184)
internal val RetroLine = Color(0xFF5E5A43)
private const val RETRO_DEAD = 50.0

internal enum class RetroTab(val label: String, val shortLabel: String) {
    DASHBOARD("TABLOU", "ACUM"),
    ENERGY("ENERGIE", "kWh"),
    SYSTEM("SISTEM", "SYS"),
    SETTINGS("SETARI", "CFG")
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
    Box(
        Modifier
            .fillMaxSize()
            .background(RetroBackground)
    ) {
        RetroBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize()
        ) {
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
            RetroBottomNavigation(selectedTab = selectedTab, onTabSelected = onTabSelected)
        }
    }
}

@Composable
private fun RetroOverviewPage(
    data: SolarData?,
    alarmThresholdW: Int,
    onEnergyFieldClick: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RetroPageHeader(
            title = "SOLAR MONITOR",
            subtitle = if (data == null) "ASTEPT DATE" else "SISTEM IN FUNCTIUNE",
            statusColor = if (data == null) RetroOlive else RetroSage
        )
        RetroLivePanel(
            data = data,
            alarmThresholdW = alarmThresholdW,
            onHouseHistoryClick = { onEnergyFieldClick("output_power") },
            onPvHistoryClick = { onEnergyFieldClick("pv_power") }
        )
        RetroFlowPanel(data = data)
        RetroReadOnlyFooter()
    }
}

@Composable
private fun RetroSystemPage(data: SolarData?, onEnergyFieldClick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RetroPageHeader(
            title = "SISTEM",
            subtitle = if (data == null) "TELEMETRIE INDISPONIBILA" else "TELEMETRIE ACTIVA",
            statusColor = if (data == null) RetroRed else RetroSage
        )
        RetroInverterStatusPanel(data)
        RetroSystemPanel(data = data, onHistoryFieldClick = onEnergyFieldClick)
        RetroReadOnlyFooter()
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
internal fun RetroPageHeader(title: String, subtitle: String, statusColor: Color = RetroSage) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = RetroYellow,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(7.dp))
                Text(
                    subtitle,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

@Composable
private fun RetroBottomNavigation(
    selectedTab: RetroTab,
    onTabSelected: (RetroTab) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = RetroPanel,
        border = BorderStroke(1.dp, RetroOlive.copy(alpha = 0.72f)),
        shadowElevation = 12.dp
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RetroTab.entries.forEach { tab ->
                    RetroNavigationItem(
                        modifier = Modifier.weight(1f),
                        tab = tab,
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) }
                    )
                }
            }
            RetroReliefEdges(Modifier.matchParentSize(), RoundedCornerShape(16.dp))
            RetroCornerScrews(Modifier.matchParentSize())
        }
    }
}

@Composable
private fun RetroNavigationItem(
    modifier: Modifier,
    tab: RetroTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(54.dp)
            .semantics {
                contentDescription = "Tab ${tab.label}${if (selected) ", selectat" else ""}"
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) RetroPanelRaised else RetroPanel.copy(alpha = 0.78f),
        border = BorderStroke(
            1.dp,
            if (selected) RetroYellow.copy(alpha = 0.58f) else RetroLine.copy(alpha = 0.60f)
        ),
        shadowElevation = if (selected) 6.dp else 2.dp
    ) {
        Box {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    tab.shortLabel,
                    color = if (selected) RetroYellow else RetroOlive,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    color = if (selected) RetroYellow else RetroMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (tab == RetroTab.SETTINGS) 8.sp else 9.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
            RetroReliefEdges(Modifier.matchParentSize(), RoundedCornerShape(9.dp), subtle = true)
        }
    }
}

@Composable
private fun RetroInverterStatusPanel(data: SolarData?) {
    RetroPanelSurface {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background((if (data == null) RetroRed else RetroSage).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (data == null) RetroRed else RetroSage)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (data == null) "FARA DATE" else "INVERTOR CONECTAT",
                    color = if (data == null) RetroRed else RetroSage,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "GROWATT SPF 6000 ES PLUS",
                    color = RetroMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    data?.let { "COD ${it.status.roundToInt()}" } ?: "COD —",
                    color = RetroYellow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    retroTimestamp(data?.timestamp),
                    color = RetroOlive,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp
                )
            }
        }
    }
}

@Composable
private fun RetroReadOnlyFooter() {
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

private fun retroTimestamp(value: String?): String {
    if (value.isNullOrBlank()) return "READ ONLY"
    return runCatching {
        OffsetDateTime.parse(value)
            .atZoneSameInstant(ZoneId.of("Europe/Bucharest"))
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }.getOrDefault("SYNC API")
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
    val loadColor = retroLoadColor(valueW ?: 0.0, alarmThresholdW)
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
                color = RetroRed.copy(alpha = 0.20f),
                startAngle = startAngle + sweep * thresholdFraction,
                sweepAngle = sweep * (1f - thresholdFraction),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = loadColor,
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
                    color = when {
                        tickFraction >= thresholdFraction -> RetroRed
                        tickFraction >= thresholdFraction * 0.8f -> RetroYellow
                        else -> RetroSage
                    },
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
                color = loadColor,
                start = center,
                end = needleEnd,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(RetroBackground, radius = 9.dp.toPx(), center = center)
            drawCircle(loadColor, radius = 5.dp.toPx(), center = center)
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
                color = loadColor,
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
                connection(gridPoint, housePoint, grid > RETRO_DEAD, RetroRed)
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
                color = RetroText
            )
            RetroFlowNode(
                modifier = Modifier.align(Alignment.BottomEnd).width(86.dp),
                symbol = "AC",
                label = "RETEA",
                value = "${retroWhole(if (data == null) null else grid)} W",
                color = RetroRed
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
internal fun RetroDailyPanel(data: SolarData?, onHistoryFieldClick: (String) -> Unit) {
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
                data?.let { "SOC ${it.batterySoc.roundToInt()}% · ${retroSigned(it.batteryDisplay)} W" } ?: "ASTEPT",
                RetroYellow,
                onClick = { onHistoryFieldClick("battery_voltage") }
            )
            RetroStatusDivider()
            RetroStatusCell(
                Modifier.weight(1f),
                "RETEA",
                data?.let { String.format(Locale.US, "%.1f V", it.gridVoltage) } ?: "—",
                data?.let { "IMPORT ${((it.gridImport + it.gridCharge).roundToInt())} W" } ?: "ASTEPT",
                RetroRed
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
internal fun RetroPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = RetroPanel,
        border = BorderStroke(1.dp, RetroOlive.copy(alpha = 0.62f)),
        shadowElevation = 8.dp
    ) {
        Box {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), content = content)
            RetroReliefEdges(Modifier.matchParentSize(), RoundedCornerShape(17.dp))
            RetroCornerScrews(Modifier.matchParentSize())
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
        val inset = if (subtle) 3.dp.toPx() else 5.dp.toPx()
        val stroke = if (subtle) 0.7.dp.toPx() else 1.dp.toPx()
        val light = RetroText.copy(alpha = if (subtle) 0.07f else 0.10f)
        val dark = RetroBackground.copy(alpha = if (subtle) 0.62f else 0.78f)
        drawLine(light, Offset(inset, inset), Offset(size.width - inset, inset), stroke)
        drawLine(light, Offset(inset, inset), Offset(inset, size.height - inset), stroke)
        drawLine(
            dark,
            Offset(inset, size.height - inset),
            Offset(size.width - inset, size.height - inset),
            stroke
        )
        drawLine(
            dark,
            Offset(size.width - inset, inset),
            Offset(size.width - inset, size.height - inset),
            stroke
        )
    }
}

@Composable
internal fun RetroCornerScrews(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val inset = 12.dp.toPx()
        val radius = 3.2.dp.toPx()
        val slot = 1.8.dp.toPx()
        val centers = listOf(
            Offset(inset, inset),
            Offset(size.width - inset, inset),
            Offset(inset, size.height - inset),
            Offset(size.width - inset, size.height - inset)
        )
        centers.forEachIndexed { index, center ->
            drawCircle(RetroBackground.copy(alpha = 0.92f), radius = radius + 1.dp.toPx(), center = center)
            drawCircle(RetroOlive, radius = radius, center = center)
            drawCircle(RetroText.copy(alpha = 0.16f), radius = radius * 0.52f, center = center)
            val direction = if (index % 2 == 0) 1f else -1f
            drawLine(
                color = RetroBackground.copy(alpha = 0.88f),
                start = Offset(center.x - slot, center.y - slot * direction),
                end = Offset(center.x + slot, center.y + slot * direction),
                strokeWidth = 0.9.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
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
    "RETEA" -> RetroRed
    else -> RetroOlive
}

private fun retroLoadColor(valueW: Double, alarmThresholdW: Int): Color = when {
    valueW >= alarmThresholdW -> RetroRed
    valueW >= alarmThresholdW * 0.8 -> RetroYellow
    else -> RetroSage
}

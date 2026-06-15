package com.rolling7.solar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val CPv = Color(0xFF69C46F)
private val CBat = Color(0xFFF4C542)
private val CGrid = Color(0xFFE85663)
private val CHouse = Color(0xFF5AA7F7)
private val CPanel = Color(0xFF121923)
private val CPanelSoft = Color(0xFF172130)
private val CBg = Color(0xFF090D12)
private val CLine = Color(0xFF263241)
private val CMuted = Color(0xFF94A3B8)
private val CText = Color(0xFFE5EDF6)
private const val DEAD = 50.0

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    var data by remember { mutableStateOf<SolarData?>(null) }
    var online by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val d = withContext(Dispatchers.IO) { SolarRepository.fetch() }
            if (d != null) {
                data = d
                online = true
            } else {
                online = false
            }
            delay(2000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CPv,
            background = CBg,
            surface = CPanel,
            onSurface = CText
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = CBg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Header(data = data, online = online)
                MainStatusPanel(data = data)
                FlowDiagram(data = data)
                MetricsGrid(data = data)
            }
        }
    }
}

@Composable
private fun Header(data: SolarData?, online: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Solar Monitor",
                color = CText,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                "Growatt SPF 6000 ES Plus",
                color = CMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        StatusPill(
            label = when {
                data == null -> "conectare"
                online -> "live"
                else -> "offline"
            },
            color = if (online) CPv else if (data == null) CMuted else CGrid
        )
    }
}

@Composable
private fun MainStatusPanel(data: SolarData?) {
    val house = data?.house ?: 0.0
    val source = sourceLabel(data)
    val sourceColor = sourceColor(data)
    val batteryVoltage = data?.batteryVoltage ?: 0.0
    val batteryLevel = batteryVoltageLevel(batteryVoltage)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CPanel)
            .border(1.dp, CLine, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text("Consum casa", color = CMuted, fontSize = 13.sp)
                Text(
                    watts(house),
                    color = CHouse,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            SourceBadge(label = source, color = sourceColor)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CompactStat(Modifier.weight(1f), "PV acum", watts(data?.pv ?: 0.0), CPv)
            CompactStat(Modifier.weight(1f), "Baterie", signedWatts(data?.batteryDisplay ?: 0.0), batteryColor(batteryVoltage))
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Baterie", color = CMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { batteryLevel },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(20.dp)),
                color = batteryColor(batteryVoltage),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
            Spacer(Modifier.width(10.dp))
            Text(String.format("%.2f V", batteryVoltage), color = CText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceBadge(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text("sursa", color = CMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun CompactStat(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CPanelSoft)
            .padding(12.dp)
    ) {
        Text(label, color = CMuted, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun FlowDiagram(data: SolarData?) {
    val pv = data?.pv ?: 0.0
    val house = data?.house ?: 0.0
    val batDisplay = data?.batteryDisplay ?: 0.0
    val batCharge = data?.batteryCharge ?: 0.0
    val batSupport = data?.batterySupport ?: 0.0
    val grid = (data?.gridImport ?: 0.0) + (data?.gridCharge ?: 0.0)
    val charging = batCharge > DEAD || batDisplay > DEAD
    val discharging = batSupport > DEAD || batDisplay < -DEAD

    val transition = rememberInfiniteTransition(label = "flow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CPanel)
            .border(1.dp, CLine, RoundedCornerShape(18.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SectionTitle("Flux energie")
        Spacer(Modifier.height(12.dp))
        FlowNode(Modifier.fillMaxWidth(0.58f), "Panouri", watts(pv), CPv, large = true)
        ArrowLine(vertical = true, active = pv > DEAD, reversed = false, color = CPv, phase = phase, arrowSize = 42.dp)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowNode(
                Modifier.weight(1f),
                if (charging) "Incarcare" else if (discharging) "Descarcare" else "Baterie",
                watts(abs(batDisplay)),
                CBat
            )
            ArrowLine(
                vertical = false,
                active = charging || discharging,
                reversed = charging,
                color = if (charging) CPv else CBat,
                phase = phase,
                arrowSize = 28.dp
            )
            FlowNode(Modifier.weight(1.08f), "Casa", watts(house), CHouse, large = true)
            ArrowLine(
                vertical = false,
                active = grid > DEAD,
                reversed = true,
                color = CGrid,
                phase = phase,
                arrowSize = 28.dp
            )
            FlowNode(Modifier.weight(1f), "Retea", watts(grid), CGrid)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = CText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FlowNode(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color,
    large: Boolean = false
) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(13.dp))
            .padding(horizontal = 7.dp, vertical = if (large) 14.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = CMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = color,
            fontSize = if (large) 22.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ArrowLine(
    vertical: Boolean,
    active: Boolean,
    reversed: Boolean,
    color: Color,
    phase: Float,
    arrowSize: Dp
) {
    val modifier = if (vertical) {
        Modifier.width(24.dp).height(arrowSize)
    } else {
        Modifier.width(arrowSize).height(30.dp)
    }
    val drawColor = if (active) color else color.copy(alpha = 0.16f)
    Canvas(modifier) {
        val effect = if (active) {
            PathEffect.dashPathEffect(floatArrayOf(11f, 8f), if (reversed) phase else -phase)
        } else {
            null
        }
        if (vertical) {
            val x = size.width / 2
            drawLine(
                drawColor,
                Offset(x, 0f),
                Offset(x, size.height),
                strokeWidth = 6f,
                cap = StrokeCap.Round,
                pathEffect = effect
            )
        } else {
            val y = size.height / 2
            drawLine(
                drawColor,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 6f,
                cap = StrokeCap.Round,
                pathEffect = effect
            )
        }
    }
}

@Composable
private fun MetricsGrid(data: SolarData?) {
    val items = listOf(
        Metric("Produs azi", String.format("%.1f kWh", data?.energyPvToday ?: 0.0), "total ${(data?.energyPvTotal ?: 0.0).roundToInt()} kWh", CPv),
        Metric("Consum azi", String.format("%.1f kWh", data?.energyLoadToday ?: 0.0), "total ${(data?.energyLoadTotal ?: 0.0).roundToInt()} kWh", CHouse),
        Metric("PV intrari", watts(data?.pv ?: 0.0), "PV1 ${watts(data?.pv1 ?: 0.0)}  |  PV2 ${watts(data?.pv2 ?: 0.0)}", CPv),
        Metric("Baterie", String.format("%.2f V", data?.batteryVoltage ?: 0.0), signedWatts(data?.batteryDisplay ?: 0.0), batteryColor(data?.batteryVoltage ?: 0.0)),
        Metric("Casa", watts(data?.house ?: 0.0), "incarcare ${(data?.loadPercent ?: 0.0).roundToInt()}%", CHouse),
        Metric("Retea", String.format("%.1f V", data?.gridVoltage ?: 0.0), "import ${watts((data?.gridImport ?: 0.0) + (data?.gridCharge ?: 0.0))}", CGrid),
        Metric("Temperatura", String.format("%.1f C", data?.inverterTemp ?: 0.0), "invertor", CHouse),
        Metric("Pierderi", watts(data?.inverterLoss ?: 0.0), "consum propriu", CMuted)
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 360.dp
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (twoColumns) {
                items.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems.forEach { item ->
                            MetricCard(Modifier.weight(1f), item)
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items.forEach { item ->
                    MetricCard(Modifier.fillMaxWidth(), item)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, metric: Metric) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CPanel)
            .border(1.dp, metric.color.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(metric.label, color = CMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(metric.value, color = metric.color, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(metric.sub, color = CMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class Metric(
    val label: String,
    val value: String,
    val sub: String,
    val color: Color
)

private fun watts(value: Double): String = "${value.roundToInt()} W"

private fun signedWatts(value: Double): String {
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded W" else "$rounded W"
}

private fun sourceLabel(data: SolarData?): String {
    if (data == null) return "astept date"
    when (data.houseSource.roundToInt()) {
        1 -> return "solar"
        2 -> return "baterie"
        3 -> return "retea"
    }
    return when {
        data.gridImport + data.gridCharge > DEAD -> "retea"
        data.batterySupport > DEAD || data.batteryDisplay < -DEAD -> "baterie"
        data.pv > DEAD -> "solar"
        else -> "standby"
    }
}

private fun sourceColor(data: SolarData?): Color {
    if (data == null) return CMuted
    when (data.houseSource.roundToInt()) {
        1 -> return CPv
        2 -> return CBat
        3 -> return CGrid
    }
    return when {
        data.gridImport + data.gridCharge > DEAD -> CGrid
        data.batterySupport > DEAD || data.batteryDisplay < -DEAD -> CBat
        data.pv > DEAD -> CPv
        else -> CMuted
    }
}

private fun batteryVoltageLevel(voltage: Double): Float =
    ((voltage - 46.0) / 12.0).toFloat().coerceIn(0f, 1f)

private fun batteryColor(voltage: Double): Color = when {
    voltage < 48.5 -> CGrid
    voltage < 51.0 -> CBat
    else -> CPv
}

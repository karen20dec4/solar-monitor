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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val CPv = Color(0xFF73BF69)
private val CBat = Color(0xFFFADE2A)
private val CGrid = Color(0xFFF2495C)
private val CHouse = Color(0xFF5794F2)
private val CBg = Color(0xFF0B0F14)
private val CMuted = Color(0xFF8B97A6)
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
            if (d != null) { data = d; online = true } else online = false
            delay(2000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(primary = CPv, background = CBg, surface = CBg)
    ) {
        Surface(Modifier.fillMaxSize(), color = CBg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("Solar — Growatt SPF 6000", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (data == null) "Conectare…" else if (online) "● live" else "○ offline — ultimele valori",
                    color = if (online) CPv else CGrid, fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                FlowDiagram(data)
                Spacer(Modifier.height(18.dp))
                CardsGrid(data)
            }
        }
    }
}

@Composable
private fun FlowDiagram(d: SolarData?) {
    val pv = d?.pv ?: 0.0
    val house = d?.house ?: 0.0
    val batDisp = d?.batteryDisplay ?: 0.0
    val batChg = d?.batteryCharge ?: 0.0
    val batSup = d?.batterySupport ?: 0.0
    val grid = (d?.gridImport ?: 0.0) + (d?.gridCharge ?: 0.0)
    val charging = batChg > DEAD || batDisp > DEAD
    val discharging = batSup > DEAD || batDisp < -DEAD
    val batLabel = if (charging) "🔋 Încarcă" else if (discharging) "🔋 Descarcă" else "🔋 Baterie"

    val transition = rememberInfiniteTransition(label = "flow")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        NodeBox(Modifier.fillMaxWidth(0.40f), "☀️ PV", pv, CPv, big = true)
        ArrowLine(vertical = true, active = pv > DEAD, reversed = false, color = CPv, phase = phase)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NodeBox(Modifier.weight(1f), batLabel, abs(batDisp), CBat)
            ArrowLine(false, charging || discharging, charging, if (charging) CPv else CBat, phase)
            NodeBox(Modifier.weight(1f), "🏠 Casă", house, CHouse, big = true)
            ArrowLine(false, grid > DEAD, true, CGrid, phase)
            NodeBox(Modifier.weight(1f), "⚡ Rețea", grid, CGrid)
        }
    }
}

@Composable
private fun NodeBox(modifier: Modifier, label: String, watts: Double, color: Color, big: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(vertical = 18.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = CMuted, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text("${watts.roundToInt()} W", color = color, fontSize = if (big) 29.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ArrowLine(vertical: Boolean, active: Boolean, reversed: Boolean, color: Color, phase: Float) {
    val mod = if (vertical) Modifier.width(24.dp).height(50.dp) else Modifier.width(26.dp).height(28.dp)
    val draw = if (active) color else color.copy(alpha = 0.16f)
    Canvas(mod) {
        val effect = if (active) PathEffect.dashPathEffect(floatArrayOf(11f, 8f), if (reversed) phase else -phase) else null
        if (vertical) {
            val x = size.width / 2
            drawLine(draw, Offset(x, 0f), Offset(x, size.height), strokeWidth = 6f, cap = StrokeCap.Round, pathEffect = effect)
        } else {
            val y = size.height / 2
            drawLine(draw, Offset(0f, y), Offset(size.width, y), strokeWidth = 6f, cap = StrokeCap.Round, pathEffect = effect)
        }
    }
}

@Composable
private fun CardsGrid(d: SolarData?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(Modifier.weight(1f), "☀️ Produs azi", String.format("%.1f kWh", d?.energyPvToday ?: 0.0),
                "total ${(d?.energyPvTotal ?: 0.0).roundToInt()} kWh", CPv)
            InfoCard(Modifier.weight(1f), "🏠 Consumat azi", String.format("%.1f kWh", d?.energyLoadToday ?: 0.0),
                "total ${(d?.energyLoadTotal ?: 0.0).roundToInt()} kWh", CHouse)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(Modifier.weight(1f), "☀️ PV", "${(d?.pv ?: 0.0).roundToInt()} W",
                "PV1 ${(d?.pv1 ?: 0.0).roundToInt()} · PV2 ${(d?.pv2 ?: 0.0).roundToInt()}", CPv)
            InfoCard(Modifier.weight(1f), "🔋 Baterie", String.format("%.2f V", d?.batteryVoltage ?: 0.0),
                "SOC ${(d?.batterySoc ?: 0.0).roundToInt()}% · ${(d?.batteryDisplay ?: 0.0).roundToInt()} W", CBat)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(Modifier.weight(1f), "🏠 Casă", "${(d?.house ?: 0.0).roundToInt()} W",
                "încărcare ${(d?.loadPercent ?: 0.0).roundToInt()}%", CHouse)
            InfoCard(Modifier.weight(1f), "⚡ Rețea", String.format("%.1f V", d?.gridVoltage ?: 0.0),
                "import ${((d?.gridImport ?: 0.0) + (d?.gridCharge ?: 0.0)).roundToInt()} W", CGrid)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(Modifier.weight(1f), "🌡️ Temp invertor", String.format("%.1f °C", d?.inverterTemp ?: 0.0), "", CHouse)
            InfoCard(Modifier.weight(1f), "⚙️ Pierderi", "${(d?.inverterLoss ?: 0.0).roundToInt()} W", "consum propriu", CMuted)
        }
    }
}

@Composable
private fun InfoCard(modifier: Modifier, label: String, value: String, sub: String, color: Color) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141B25))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(label, color = CMuted, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(sub, color = CMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

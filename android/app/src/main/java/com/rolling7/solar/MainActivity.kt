package com.rolling7.solar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    var data by remember { mutableStateOf<SolarData?>(null) }
    var online by remember { mutableStateOf(false) }
    var selectedHistory by remember { mutableStateOf<HistoryMetric?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var alarmSettings by remember { mutableStateOf(AlarmSettingsStore.read(context)) }
    var enableAfterNotificationPermission by remember { mutableStateOf(false) }

    fun saveAlarmSettings(next: AlarmSettings, applyService: Boolean = true) {
        alarmSettings = next
        AlarmSettingsStore.save(context, next)
        if (applyService) {
            AlarmSettingsStore.applyServiceState(context, next)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && enableAfterNotificationPermission) {
            saveAlarmSettings(alarmSettings.copy(enabled = true))
        }
        enableAfterNotificationPermission = false
    }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = pickedRingtoneUri(result.data)
        saveAlarmSettings(alarmSettings.copy(ringtoneUri = uri?.toString()), applyService = false)
    }

    LaunchedEffect(Unit) {
        if (alarmSettings.enabled) {
            AlarmSettingsStore.applyServiceState(context, alarmSettings)
        }
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
                Header(data = data, online = online, onSettingsClick = { showSettings = true })
                MainStatusPanel(data = data)
                FlowDiagram(data = data)
                MetricsGrid(data = data, onHistoryClick = { selectedHistory = it })
            }
        }

        selectedHistory?.let { metric ->
            ModalBottomSheet(
                onDismissRequest = { selectedHistory = null },
                containerColor = CPanel,
                contentColor = CText
            ) {
                HistorySheet(metric = metric)
            }
        }

        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                containerColor = CPanel,
                contentColor = CText
            ) {
                SettingsSheet(
                    settings = alarmSettings,
                    ringtoneTitle = AlarmSettingsStore.ringtoneTitle(context, alarmSettings),
                    version = appVersion(context),
                    onEnabledChange = { enabled ->
                        if (enabled && !hasNotificationPermission(context)) {
                            enableAfterNotificationPermission = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            saveAlarmSettings(alarmSettings.copy(enabled = enabled))
                        }
                    },
                    onThresholdChange = { saveAlarmSettings(alarmSettings.copy(thresholdW = it), applyService = false) },
                    onCooldownChange = { saveAlarmSettings(alarmSettings.copy(cooldownS = it), applyService = false) },
                    onVibrateChange = { saveAlarmSettings(alarmSettings.copy(vibrate = it), applyService = false) },
                    onPickRingtone = { ringtoneLauncher.launch(ringtonePickerIntent(alarmSettings)) },
                    onTestAlarm = { AlarmSettingsStore.testAlarm(context) }
                )
            }
        }
    }
}

@Composable
private fun Header(data: SolarData?, online: Boolean, onSettingsClick: () -> Unit) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                label = when {
                    data == null -> "conectare"
                    online -> "live"
                    else -> "offline"
                },
                color = if (online) CPv else if (data == null) CMuted else CGrid
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(CPanel)
                    .border(1.dp, CLine, CircleShape)
                    .clickable(onClick = onSettingsClick),
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", color = CText, fontSize = 18.sp)
            }
        }
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
private fun MetricsGrid(data: SolarData?, onHistoryClick: (HistoryMetric) -> Unit) {
    val pvEnergyHistory = HistoryMetric(
        title = "Produs",
        field = "energy_pv_today",
        unit = "kWh",
        color = CPv,
        defaultRange = "7d",
        ranges = listOf("7d", "30d"),
        chartStyle = ChartStyle.Bar
    )
    val loadEnergyHistory = HistoryMetric(
        title = "Consum",
        field = "energy_load_today",
        unit = "kWh",
        color = CHouse,
        defaultRange = "7d",
        ranges = listOf("7d", "30d"),
        chartStyle = ChartStyle.Bar
    )
    val batteryHistory = HistoryMetric(
        title = "Baterie",
        field = "battery_voltage",
        unit = "V",
        color = CBat,
        defaultRange = "24h",
        ranges = listOf("1h", "6h", "24h"),
        thresholds = listOf(
            ChartThreshold(48.0, CGrid),
            ChartThreshold(57.0, CGrid)
        )
    )
    val houseHistory = HistoryMetric(
        title = "Consum casa",
        field = "output_power",
        unit = "W",
        color = CHouse,
        defaultRange = "1h",
        ranges = listOf("1h", "6h", "24h")
    )
    val pvHistory = HistoryMetric(
        title = "PV intrari",
        field = "pv_power",
        unit = "W",
        color = CPv,
        defaultRange = "24h",
        ranges = listOf("1h", "6h", "24h")
    )

    val items = listOf(
        Metric("Produs azi", String.format("%.1f kWh", data?.energyPvToday ?: 0.0), "total ${(data?.energyPvTotal ?: 0.0).roundToInt()} kWh", CPv, pvEnergyHistory),
        Metric("Consum azi", String.format("%.1f kWh", data?.energyLoadToday ?: 0.0), "total ${(data?.energyLoadTotal ?: 0.0).roundToInt()} kWh", CHouse, loadEnergyHistory),
        Metric("PV intrari", watts(data?.pv ?: 0.0), "PV1 ${watts(data?.pv1 ?: 0.0)}  |  PV2 ${watts(data?.pv2 ?: 0.0)}", CPv, pvHistory),
        Metric("Baterie", String.format("%.2f V", data?.batteryVoltage ?: 0.0), signedWatts(data?.batteryDisplay ?: 0.0), batteryColor(data?.batteryVoltage ?: 0.0), batteryHistory),
        Metric("Casa", watts(data?.house ?: 0.0), "incarcare ${(data?.loadPercent ?: 0.0).roundToInt()}%", CHouse, houseHistory),
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
                            MetricCard(Modifier.weight(1f), item, onHistoryClick)
                        }
                        if (rowItems.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items.forEach { item ->
                    MetricCard(Modifier.fillMaxWidth(), item, onHistoryClick)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    metric: Metric,
    onHistoryClick: (HistoryMetric) -> Unit
) {
    val clickModifier = metric.history?.let { history ->
        Modifier.clickable { onHistoryClick(history) }
    } ?: Modifier

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CPanel)
            .border(1.dp, metric.color.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .then(clickModifier)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                metric.label,
                color = CMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (metric.history != null) {
                Text("istoric", color = metric.color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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
    val color: Color,
    val history: HistoryMetric? = null
)

private data class HistoryMetric(
    val title: String,
    val field: String,
    val unit: String,
    val color: Color,
    val defaultRange: String,
    val ranges: List<String>,
    val chartStyle: ChartStyle = ChartStyle.Line,
    val thresholds: List<ChartThreshold> = emptyList()
)

private enum class ChartStyle { Line, Bar }

private data class ChartThreshold(val value: Double, val color: Color)

private data class LineAxis(
    val min: Double,
    val max: Double,
    val gridValues: List<Double>,
    val title: String
)

private data class TimeTick(val timeMs: Long, val label: String)

private val LocalZone: ZoneId = ZoneId.of("Europe/Bucharest")
private val HourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun SettingsSheet(
    settings: AlarmSettings,
    ringtoneTitle: String,
    version: String,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onTestAlarm: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Setari", color = CText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Alarma locala ruleaza pe telefon prin foreground service.", color = CMuted, fontSize = 12.sp)

        HorizontalDivider(color = CLine)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Alarma consum mare", color = CText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (settings.enabled) "Activa - service permanent" else "Oprita",
                    color = if (settings.enabled) CPv else CMuted,
                    fontSize = 12.sp
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
        }

        SettingSlider(
            title = "Prag alarma",
            value = settings.thresholdW,
            valueLabel = "${settings.thresholdW} W",
            range = 3000f..6500f,
            step = 100,
            onChange = onThresholdChange
        )
        Text("Rearmare la ${settings.clearThresholdW} W.", color = CMuted, fontSize = 12.sp)

        SettingSlider(
            title = "Cooldown",
            value = settings.cooldownS,
            valueLabel = "${settings.cooldownS}s",
            range = 60f..600f,
            step = 30,
            onChange = onCooldownChange
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vibratie", color = CText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Porneste o vibratie scurta cand alarma suna.", color = CMuted, fontSize = 12.sp)
            }
            Switch(checked = settings.vibrate, onCheckedChange = onVibrateChange)
        }

        HorizontalDivider(color = CLine)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sunet alarma", color = CText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(ringtoneTitle, color = CMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickRingtone, modifier = Modifier.weight(1f)) {
                    Text("Alege sunet")
                }
                Button(onClick = onTestAlarm, modifier = Modifier.weight(1f)) {
                    Text("Testeaza")
                }
            }
        }

        HorizontalDivider(color = CLine)

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Aplicatie", color = CText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Versiune $version", color = CMuted, fontSize = 12.sp)
            Text("Endpoint: vyra.go.ro:31443", color = CMuted, fontSize = 12.sp)
            Text("Polling alarma: 2s prin API, nu direct invertor.", color = CMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Int,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    onChange: (Int) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = CText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, color = CPv, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val rounded = (raw / step).roundToInt() * step
                onChange(rounded.coerceIn(range.start.roundToInt(), range.endInclusive.roundToInt()))
            },
            valueRange = range
        )
    }
}

@Composable
private fun HistorySheet(metric: HistoryMetric) {
    var selectedRange by remember(metric.field) { mutableStateOf(metric.defaultRange) }
    var series by remember(metric.field) { mutableStateOf<HistorySeries?>(null) }
    var loading by remember(metric.field) { mutableStateOf(false) }
    var error by remember(metric.field) { mutableStateOf<String?>(null) }

    LaunchedEffect(metric.field, selectedRange) {
        loading = true
        error = null
        series = null
        val result = withContext(Dispatchers.IO) {
            SolarRepository.fetchHistory(metric.field, selectedRange)
        }
        if (result == null) {
            error = "Nu pot incarca istoricul"
        } else {
            series = result
        }
        loading = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 28.dp)
    ) {
        Text(metric.title, color = CText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            historySubtitle(metric),
            color = CMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        RangeSelector(
            ranges = metric.ranges,
            selectedRange = selectedRange,
            color = metric.color
        ) { selectedRange = it }
        Spacer(Modifier.height(16.dp))

        when {
            loading -> {
                Text("Se incarca...", color = CMuted, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
            }
            error != null -> {
                Text(error ?: "", color = CGrid, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
            }
            series == null || series?.points?.isEmpty() == true -> {
                Text("Fara date pentru intervalul ales", color = CMuted, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
            }
            else -> {
                val loaded = series
                if (loaded != null) {
                    HistoryChart(series = loaded, metric = metric)
                    Spacer(Modifier.height(14.dp))
                    loaded.stats?.let { stats ->
                        HistoryStatsGrid(stats = stats, metric = metric)
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeSelector(
    ranges: List<String>,
    selectedRange: String,
    color: Color,
    onRangeClick: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEach { range ->
            RangeChip(
                modifier = Modifier.weight(1f),
                label = range,
                selected = range == selectedRange,
                color = color,
                onClick = { onRangeClick(range) }
            )
        }
    }
}

@Composable
private fun RangeChip(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val bg = if (selected) color.copy(alpha = 0.18f) else CPanelSoft
    val border = if (selected) color.copy(alpha = 0.70f) else CLine
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) color else CMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryChart(series: HistorySeries, metric: HistoryMetric) {
    if (metric.chartStyle == ChartStyle.Bar) {
        BarHistoryChart(series = series, metric = metric)
        return
    }

    val values = series.points.map { it.value }
    val axis = lineAxis(metric, values)
    val timeTicks = timeTicks(series)
    val pointTimes = series.points.map { parsePointMillis(it.time) }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(axis.title, color = CMuted, fontSize = 11.sp)
            Text("${series.points.size} puncte", color = CMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CBg)
                .border(1.dp, CLine, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            val leftPad = 38f * density
            val rightPad = 7f * density
            val topPad = 8f * density
            val bottomPad = 24f * density
            val plotLeft = leftPad
            val plotRight = size.width - rightPad
            val plotTop = topPad
            val plotBottom = size.height - bottomPad
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val firstMs = pointTimes.firstOrNull()
            val lastMs = pointTimes.lastOrNull()

            val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CMuted.toArgb()
                textSize = 10f * density
                textAlign = Paint.Align.LEFT
            }
            val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CMuted.toArgb()
                textSize = 10f * density
                textAlign = Paint.Align.CENTER
            }

            fun yFor(value: Double): Float {
                val normalized = ((value - axis.min) / (axis.max - axis.min)).toFloat()
                return plotBottom - normalized.coerceIn(0f, 1f) * plotHeight
            }

            fun xFor(index: Int): Float {
                val time = pointTimes.getOrNull(index)
                if (time != null && firstMs != null && lastMs != null && lastMs > firstMs) {
                    val normalized = ((time - firstMs).toDouble() / (lastMs - firstMs).toDouble()).toFloat()
                    return plotLeft + normalized.coerceIn(0f, 1f) * plotWidth
                }
                return if (series.points.size <= 1) {
                    plotLeft + plotWidth / 2f
                } else {
                    plotLeft + plotWidth * index / series.points.lastIndex.toFloat()
                }
            }

            axis.gridValues.forEach { value ->
                val y = yFor(value)
                drawLine(
                    color = CLine.copy(alpha = 0.55f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1.2f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    formatAxisValue(value, metric.unit),
                    2f * density,
                    y - 4f,
                    yPaint
                )
            }

            if (firstMs != null && lastMs != null && lastMs > firstMs) {
                timeTicks.forEach { tick ->
                    val normalized = ((tick.timeMs - firstMs).toDouble() / (lastMs - firstMs).toDouble()).toFloat()
                    val x = plotLeft + normalized.coerceIn(0f, 1f) * plotWidth
                    drawLine(
                        color = CLine.copy(alpha = 0.24f),
                        start = Offset(x, plotTop),
                        end = Offset(x, plotBottom),
                        strokeWidth = 1f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        tick.label,
                        x,
                        size.height - 5f * density,
                        xPaint
                    )
                }
            }

            metric.thresholds.forEach { threshold ->
                if (threshold.value in axis.min..axis.max) {
                    val y = yFor(threshold.value)
                    drawLine(
                        color = threshold.color.copy(alpha = 0.70f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )
                }
            }

            if (series.points.size == 1) {
                drawCircle(metric.color, radius = 5f, center = Offset(plotLeft + plotWidth / 2f, yFor(values.first())))
            } else {
                val path = Path()
                series.points.forEachIndexed { index, point ->
                    val x = xFor(index)
                    val y = yFor(point.value)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = metric.color,
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
                val last = series.points.last()
                drawCircle(
                    color = metric.color,
                    radius = 5f,
                    center = Offset(xFor(series.points.lastIndex), yFor(last.value))
                )
            }
        }
    }
}

@Composable
private fun BarHistoryChart(series: HistorySeries, metric: HistoryMetric) {
    val values = series.points.map { it.value.coerceAtLeast(0.0) }
    val maxValue = max(values.maxOrNull() ?: 1.0, 1.0)

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatHistoryValue(maxValue, metric.unit), color = CMuted, fontSize = 11.sp)
            Text("${series.points.size} zile", color = CMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CBg)
                .border(1.dp, CLine, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            val width = size.width
            val height = size.height
            for (i in 0..3) {
                val y = height * i / 3f
                drawLine(
                    color = CLine.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.2f
                )
            }
            if (values.isNotEmpty()) {
                val slot = width / values.size
                val barWidth = (slot * 0.62f).coerceAtLeast(3f)
                values.forEachIndexed { index, value ->
                    val x = slot * index + slot / 2f
                    val y = height - ((value / maxValue).toFloat().coerceIn(0f, 1f) * height)
                    drawLine(
                        color = metric.color,
                        start = Offset(x, height),
                        end = Offset(x, y),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Butt
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("0 ${metric.unit}", color = CMuted, fontSize = 11.sp)
    }
}

@Composable
private fun HistoryStatsGrid(stats: HistoryStats, metric: HistoryMetric) {
    if (metric.chartStyle == ChartStyle.Bar) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Modifier.weight(1f), "Total", formatHistoryValue(stats.sum, metric.unit), metric.color)
                StatTile(Modifier.weight(1f), "Medie/zi", formatHistoryValue(stats.avg, metric.unit), metric.color)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Modifier.weight(1f), "Max zi", formatHistoryValue(stats.max, metric.unit), metric.color)
                StatTile(Modifier.weight(1f), "Ultima zi", formatHistoryValue(stats.last, metric.unit), metric.color)
            }
        }
        return
    }

    val maxLabel = if (metric.unit == "W") "Varf" else "Max"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), "Ultim", formatHistoryValue(stats.last, metric.unit), metric.color)
            StatTile(Modifier.weight(1f), "Min", formatHistoryValue(stats.min, metric.unit), metric.color)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), "Medie", formatHistoryValue(stats.avg, metric.unit), metric.color)
            StatTile(Modifier.weight(1f), maxLabel, formatHistoryValue(stats.max, metric.unit), metric.color)
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CPanelSoft)
            .padding(12.dp)
    ) {
        Text(label, color = CMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun watts(value: Double): String = "${value.roundToInt()} W"

private fun lineAxis(metric: HistoryMetric, values: List<Double>): LineAxis {
    if (metric.field == "battery_voltage") {
        return LineAxis(
            min = 48.0,
            max = 58.0,
            gridValues = listOf(58.0, 56.0, 54.0, 52.0, 50.0, 48.0),
            title = "48-58 V"
        )
    }

    val maxValue = max(values.maxOrNull() ?: 1.0, 1.0)
    val step = niceStep(maxValue / 4.0)
    val top = max(step * 4.0, ceil(maxValue / step) * step)
    val grid = (4 downTo 0).map { top * it / 4.0 }
    return LineAxis(
        min = 0.0,
        max = top,
        gridValues = grid,
        title = "0-${formatAxisValue(top, metric.unit)}"
    )
}

private fun niceStep(roughStep: Double): Double {
    if (roughStep <= 0.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val normalized = roughStep / magnitude
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

private fun parsePointMillis(value: String): Long? =
    try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

private fun timeTicks(series: HistorySeries): List<TimeTick> {
    val first = series.points.firstOrNull()?.time?.let(::parsePointMillis) ?: return emptyList()
    val last = series.points.lastOrNull()?.time?.let(::parsePointMillis) ?: return emptyList()
    if (last <= first) return emptyList()

    val stepMinutes = when (series.range) {
        "1h" -> 10L
        "6h" -> 60L
        "24h" -> 180L
        else -> 60L
    }
    val formatter = if (series.range == "24h") HourFormatter else TimeFormatter
    var tick = floorToStep(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(first), LocalZone), stepMinutes)
    if (tick.toInstant().toEpochMilli() < first) {
        tick = tick.plusMinutes(stepMinutes)
    }

    val out = mutableListOf<TimeTick>()
    while (tick.toInstant().toEpochMilli() <= last && out.size < 16) {
        out += TimeTick(tick.toInstant().toEpochMilli(), tick.format(formatter))
        tick = tick.plusMinutes(stepMinutes)
    }
    return out
}

private fun floorToStep(time: OffsetDateTime, stepMinutes: Long): OffsetDateTime {
    val dayMinute = time.hour * 60 + time.minute
    val floored = dayMinute - (dayMinute % stepMinutes.toInt())
    return time
        .withHour(floored / 60)
        .withMinute(floored % 60)
        .withSecond(0)
        .withNano(0)
}

private fun formatAxisValue(value: Double, unit: String): String = when (unit) {
    "V" -> "${value.roundToInt()}V"
    "W" -> "${value.roundToInt()}W"
    "kWh" -> String.format("%.1fkWh", value)
    else -> String.format("%.1f%s", value, unit)
}

private fun formatHistoryValue(value: Double, unit: String): String = when (unit) {
    "V" -> String.format("%.2f V", value)
    "W" -> "${value.roundToInt()} W"
    "kWh" -> String.format("%.1f kWh", value)
    else -> String.format("%.1f %s", value, unit)
}

private fun historySubtitle(metric: HistoryMetric): String = when (metric.field) {
    "battery_voltage" -> "Tensiune baterie cu praguri 48V / 57V"
    "output_power" -> "Consum casa si varf maxim"
    "pv_power" -> "Productie PV si varf maxim"
    "energy_pv_today" -> "Productie zilnica pe ultimele zile"
    "energy_load_today" -> "Consum zilnic pe ultimele zile"
    else -> "Istoric"
}

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

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun ringtonePickerIntent(settings: AlarmSettings): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alege sunet alarma")
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmSettingsStore.ringtoneUri(settings))
    }

private fun pickedRingtoneUri(intent: Intent?): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }

private fun appVersion(context: Context): String =
    try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (e: Exception) {
        "necunoscuta"
    }

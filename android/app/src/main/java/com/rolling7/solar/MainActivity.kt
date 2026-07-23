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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
private val CPanelRaised = Color(0xFF1B2635)
private val CBg = Color(0xFF090D12)
private val CLine = Color(0xFF263241)
private val CMuted = Color(0xFF94A3B8)
private val CText = Color(0xFFE5EDF6)
private const val DEAD = 50.0

private data class DashboardChrome(
    val background: Color,
    val panel: Color,
    val raised: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val danger: Color,
    val font: FontFamily
)

private fun dashboardChrome(retro: Boolean): DashboardChrome = if (retro) {
    DashboardChrome(
        background = RetroBackground,
        panel = RetroPanel,
        raised = RetroPanelRaised,
        line = RetroLine,
        text = RetroText,
        muted = RetroMuted,
        danger = RetroRed,
        font = FontFamily.Monospace
    )
} else {
    DashboardChrome(
        background = CBg,
        panel = CPanel,
        raised = CPanelSoft,
        line = CLine,
        text = CText,
        muted = CMuted,
        danger = CGrid,
        font = FontFamily.Default
    )
}

private fun historyAccent(field: String, simpleColor: Color, retro: Boolean): Color {
    if (!retro) return simpleColor
    return when (field) {
        "pv_power", "energy_pv_today" -> RetroSage
        "battery_voltage" -> RetroYellow
        "output_power", "energy_load_today" -> RetroHouseBlue
        else -> RetroText
    }
}

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
    var selectedHistory by remember { mutableStateOf<HistoryMetric?>(null) }
    var showHistoryMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var retroTabName by rememberSaveable { mutableStateOf(RetroTab.DASHBOARD.name) }
    var selectedEnergyField by rememberSaveable { mutableStateOf("output_power") }
    var alarmSettings by remember { mutableStateOf(AlarmSettingsStore.read(context)) }
    var dashboardStyle by remember { mutableStateOf(DashboardStyleStore.read(context)) }
    var enableAfterNotificationPermission by remember { mutableStateOf(false) }
    val alarmRinging by AlarmState.ringing.collectAsState()
    val alarmMessage by AlarmState.message.collectAsState()
    val retro = dashboardStyle == DashboardStyle.RETRO
    val chrome = dashboardChrome(retro)

    fun saveAlarmSettings(next: AlarmSettings, applyService: Boolean = true) {
        alarmSettings = next
        AlarmSettingsStore.save(context, next)
        if (applyService) {
            AlarmSettingsStore.applyServiceState(context, next)
        }
    }

    fun saveDashboardStyle(next: DashboardStyle) {
        dashboardStyle = next
        DashboardStyleStore.save(context, next)
    }

    fun changeDashboardStyle(next: DashboardStyle) {
        if (next == dashboardStyle) return
        if (next == DashboardStyle.RETRO) {
            retroTabName = RetroTab.SETTINGS.name
            showSettings = false
        } else if (dashboardStyle == DashboardStyle.RETRO) {
            showSettings = true
        }
        saveDashboardStyle(next)
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
            }
            delay(2000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = if (retro) RetroSage else CPv,
            secondary = if (retro) RetroYellow else CHouse,
            error = chrome.danger,
            background = chrome.background,
            surface = chrome.panel,
            surfaceVariant = chrome.raised,
            onSurface = chrome.text,
            onBackground = chrome.text
        )
    ) {
        when (dashboardStyle) {
            DashboardStyle.RETRO -> RetroDashboard(
                data = data,
                alarmThresholdW = alarmSettings.thresholdW,
                selectedTab = RetroTab.valueOf(retroTabName),
                onTabSelected = { tab -> retroTabName = tab.name },
                onEnergyFieldClick = { field ->
                    selectedEnergyField = field
                    retroTabName = RetroTab.ENERGY.name
                },
                energyContent = {
                    RetroEnergyPage(
                        data = data,
                        selectedMetric = historyMetric(selectedEnergyField),
                        onMetricSelected = { metric -> selectedEnergyField = metric.field }
                    )
                },
                settingsContent = {
                    SettingsSheet(
                        dashboardStyle = dashboardStyle,
                        settings = alarmSettings,
                        ringtoneTitle = AlarmSettingsStore.ringtoneTitle(context, alarmSettings),
                        version = appVersion(context),
                        onDashboardStyleChange = ::changeDashboardStyle,
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
            )
            DashboardStyle.SIMPLE -> SimpleDashboard(
                data = data,
                onHistoryClick = { showHistoryMenu = true },
                onHistoryFieldClick = { selectedHistory = it },
                onSettingsClick = { showSettings = true }
            )
        }

        if (!retro && showHistoryMenu) {
            ModalBottomSheet(
                onDismissRequest = { showHistoryMenu = false },
                containerColor = chrome.panel,
                contentColor = chrome.text,
                dragHandle = { DashboardSheetHandle(retro) }
            ) {
                HistoryMenuSheet(
                    metrics = DashboardHistoryMetrics,
                    retro = retro,
                    onMetricClick = { metric ->
                        showHistoryMenu = false
                        selectedHistory = metric
                    }
                )
            }
        }

        if (!retro) {
            selectedHistory?.let { metric ->
                ModalBottomSheet(
                    onDismissRequest = { selectedHistory = null },
                    containerColor = chrome.panel,
                    contentColor = chrome.text,
                    dragHandle = { DashboardSheetHandle(retro) }
                ) {
                    HistorySheet(metric = metric, retro = retro)
                }
            }
        }

        if (!retro && showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                containerColor = chrome.panel,
                contentColor = chrome.text,
                dragHandle = { DashboardSheetHandle(retro) }
            ) {
                SettingsSheet(
                    dashboardStyle = dashboardStyle,
                    settings = alarmSettings,
                    ringtoneTitle = AlarmSettingsStore.ringtoneTitle(context, alarmSettings),
                    version = appVersion(context),
                    onDashboardStyleChange = ::changeDashboardStyle,
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

        if (alarmRinging) {
            AlarmOverlay(
                message = alarmMessage,
                onStop = {
                    context.startService(
                        Intent(context, SolarAlarmService::class.java)
                            .setAction(SolarAlarmService.ACTION_SILENCE)
                    )
                    AlarmState.onRingStop()
                }
            )
        }
    }
}

@Composable
private fun DashboardSheetHandle(retro: Boolean) {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .width(44.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (retro) RetroOlive else CMuted.copy(alpha = 0.72f))
    )
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun RetroEnergyPage(
    data: SolarData?,
    selectedMetric: HistoryMetric,
    onMetricSelected: (HistoryMetric) -> Unit
) {
    RetroEnergyArtworkPage(
        data = data,
        onHistoryFieldClick = { field -> onMetricSelected(historyMetric(field)) }
    )
}

@Composable
private fun RetroEnergyMetricSelector(
    selectedMetric: HistoryMetric,
    onMetricSelected: (HistoryMetric) -> Unit,
    modifier: Modifier = Modifier
) {
    RetroPanelSurface(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        fillContent = true
    ) {
        Text(
            "GRAFIC SELECTAT",
            color = RetroMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DashboardHistoryMetrics.take(3).forEach { metric ->
                RetroMetricButton(
                    modifier = Modifier.weight(1f),
                    metric = metric,
                    selected = metric.field == selectedMetric.field,
                    onClick = { onMetricSelected(metric) }
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DashboardHistoryMetrics.drop(3).forEach { metric ->
                RetroMetricButton(
                    modifier = Modifier.weight(1f),
                    metric = metric,
                    selected = metric.field == selectedMetric.field,
                    onClick = { onMetricSelected(metric) }
                )
            }
        }
    }
}

@Composable
private fun RetroMetricButton(
    modifier: Modifier,
    metric: HistoryMetric,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = historyAccent(metric.field, metric.color, retro = true)
    val label = when (metric.field) {
        "output_power" -> "CASA"
        "pv_power" -> "PANOURI"
        "battery_voltage" -> "BATERIE"
        "energy_pv_today" -> "PRODUS ZILNIC"
        "energy_load_today" -> "CONSUM ZILNIC"
        else -> metric.title.uppercase(Locale.getDefault())
    }
    RetroMetalButton(
        modifier = modifier.height(32.dp),
        selected = selected,
        accent = accent,
        description = "Grafic $label${if (selected) ", selectat" else ""}",
        onClick = onClick
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) accent else RetroMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SimpleDashboard(
    data: SolarData?,
    onHistoryClick: () -> Unit,
    onHistoryFieldClick: (HistoryMetric) -> Unit,
    onSettingsClick: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = CBg) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(
                onHistoryClick = onHistoryClick,
                onSettingsClick = onSettingsClick
            )
            EnergyOverview(data = data, onHistoryClick = onHistoryFieldClick)
            SystemDetails(data = data, onHistoryClick = onHistoryFieldClick)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun AlarmOverlay(message: String?, onStop: () -> Unit) {
    Dialog(onDismissRequest = onStop) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CPanel)
                .border(2.dp, CGrid, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "⚠ Alarma consum mare",
                color = CGrid,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message ?: "Consum mare casa",
                color = CText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CGrid,
                    contentColor = Color.White
                )
            ) {
                Text("OPRESTE ALARMA", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Header(onHistoryClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        HistoryHeaderButton(onClick = onHistoryClick)
        HeaderIconButton(description = "Setari", onClick = onSettingsClick) {
            SettingsGlyph(Modifier.size(18.dp), CMuted)
        }
    }
}

@Composable
private fun HistoryHeaderButton(onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CPanel)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Deschide istoricul" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        TrendGlyph(Modifier.size(16.dp), CPv)
        Text("Istoric", color = CText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HeaderIconButton(
    description: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(CPanel)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
private fun SettingsGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = 1.7.dp.toPx()
        val knob = 2.4.dp.toPx()
        val ys = listOf(size.height * 0.24f, size.height * 0.50f, size.height * 0.76f)
        val xs = listOf(size.width * 0.67f, size.width * 0.34f, size.width * 0.60f)
        ys.forEachIndexed { index, y ->
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(CPanel, radius = knob + stroke, center = Offset(xs[index], y))
            drawCircle(color, radius = knob, center = Offset(xs[index], y))
        }
    }
}

@Composable
private fun EnergyOverview(data: SolarData?, onHistoryClick: (HistoryMetric) -> Unit) {
    val source = sourceLabel(data)
    val sourceStatus = if (data == null) "Se conecteaza" else "Casa din $source"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = CPanel,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Acum", color = CText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Flux energetic live", color = CMuted, fontSize = 11.sp)
                }
                StatusPill(label = sourceStatus, color = sourceColor(data))
            }

            Spacer(Modifier.height(14.dp))
            EnergyFlow(data = data, onHistoryClick = onHistoryClick)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = CLine.copy(alpha = 0.65f))
            Spacer(Modifier.height(14.dp))
            DailySummary(data = data, onHistoryClick = onHistoryClick)
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun EnergyFlow(data: SolarData?, onHistoryClick: (HistoryMetric) -> Unit) {
    val pv = data?.pv ?: 0.0
    val battery = data?.batteryDisplay ?: 0.0
    val batteryCharge = data?.batteryCharge ?: 0.0
    val batterySupport = data?.batterySupport ?: 0.0
    val grid = (data?.gridImport ?: 0.0) + (data?.gridCharge ?: 0.0)
    val charging = batteryCharge > DEAD || battery > DEAD
    val discharging = batterySupport > DEAD || battery < -DEAD
    val phase by rememberInfiniteTransition(label = "flux energie").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pozitie particule"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(272.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val solar = Offset(50.dp.toPx(), 43.dp.toPx())
            val batteryNode = Offset(50.dp.toPx(), size.height - 43.dp.toPx())
            val house = Offset(size.width / 2f, size.height / 2f)
            val gridNode = Offset(size.width - 40.dp.toPx(), size.height / 2f)

            fun connection(start: Offset, end: Offset, active: Boolean, color: Color) {
                drawLine(
                    color = CLine.copy(alpha = 0.85f),
                    start = start,
                    end = end,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                if (!active) return

                drawLine(
                    color = color.copy(alpha = 0.24f),
                    start = start,
                    end = end,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                repeat(3) { index ->
                    val progress = (phase + index / 3f) % 1f
                    val x = start.x + (end.x - start.x) * progress
                    val y = start.y + (end.y - start.y) * progress
                    val point = Offset(x, y)
                    drawCircle(color.copy(alpha = 0.16f), radius = 6.dp.toPx(), center = point)
                    drawCircle(color, radius = 2.6.dp.toPx(), center = point)
                }
            }

            connection(solar, house, active = pv > DEAD, color = CPv)
            connection(solar, batteryNode, active = charging, color = CPv)
            connection(batteryNode, house, active = discharging, color = CBat)
            connection(gridNode, house, active = grid > DEAD, color = CGrid)
        }

        EnergyNode(
            modifier = Modifier.align(Alignment.TopStart).width(100.dp),
            label = "Panouri",
            number = wholeNumber(data?.pv),
            unit = "W",
            supporting = data?.let { "PV1 ${it.pv1.roundToInt()} · PV2 ${it.pv2.roundToInt()}" } ?: "astept date",
            color = CPv,
            onClick = { onHistoryClick(historyMetric("pv_power")) }
        )
        EnergyNode(
            modifier = Modifier.align(Alignment.BottomStart).width(100.dp),
            label = "Baterie",
            number = signedNumber(data?.batteryDisplay),
            unit = "W",
            supporting = data?.let { String.format(Locale.US, "%.2f V", it.batteryVoltage) } ?: "astept date",
            color = data?.let { batteryColor(it.batteryVoltage) } ?: CMuted,
            onClick = { onHistoryClick(historyMetric("battery_voltage")) }
        )
        EnergyNode(
            modifier = Modifier.align(Alignment.Center).width(116.dp),
            label = "Casa",
            number = wholeNumber(data?.house),
            unit = "W",
            supporting = data?.let { "sarcina ${it.loadPercent.roundToInt()}%" } ?: "astept date",
            color = CHouse,
            prominent = true,
            onClick = { onHistoryClick(historyMetric("output_power")) }
        )
        EnergyNode(
            modifier = Modifier.align(Alignment.CenterEnd).width(80.dp),
            label = "Retea",
            number = wholeNumber(if (data == null) null else grid),
            unit = "W",
            supporting = data?.let { String.format(Locale.US, "%.1f V", it.gridVoltage) } ?: "astept",
            color = CGrid
        )
    }
}

@Composable
private fun EnergyNode(
    modifier: Modifier,
    label: String,
    number: String,
    unit: String,
    supporting: String,
    color: Color,
    prominent: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Surface(
        modifier = modifier
            .heightIn(min = if (prominent) 94.dp else 86.dp)
            .then(clickModifier),
        shape = RoundedCornerShape(if (prominent) 20.dp else 17.dp),
        color = if (prominent) CPanelRaised else CPanelSoft,
        tonalElevation = if (prominent) 4.dp else 1.dp,
        shadowElevation = if (prominent) 2.dp else 0.dp
    ) {
        Column(
            Modifier.padding(horizontal = if (prominent) 12.dp else 9.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, color = CMuted, fontSize = 11.sp, maxLines = 1)
                if (onClick != null) {
                    Spacer(Modifier.width(5.dp))
                    TrendGlyph(Modifier.size(12.dp), color.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.height(4.dp))
            MeasurementText(number = number, unit = unit, color = color, prominent = prominent)
            Spacer(Modifier.height(3.dp))
            Text(
                supporting,
                color = CMuted,
                fontSize = if (prominent) 10.sp else 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MeasurementText(number: String, unit: String, color: Color, prominent: Boolean = false) {
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontSize = if (prominent) 28.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            ) { append(number) }
            append(" ")
            withStyle(
                SpanStyle(
                    fontSize = if (prominent) 13.sp else 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = CMuted
                )
            ) { append(unit) }
        },
        maxLines = 1
    )
}

@Composable
private fun DailySummary(data: SolarData?, onHistoryClick: (HistoryMetric) -> Unit) {
    Column {
        Text("Astazi", color = CText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DailyMetric(
                modifier = Modifier.weight(1f),
                label = "Produs",
                number = decimalNumber(data?.energyPvToday, 1),
                total = data?.let { "total ${it.energyPvTotal.roundToInt()} kWh" } ?: "astept date",
                color = CPv,
                onClick = { onHistoryClick(historyMetric("energy_pv_today")) }
            )
            Box(Modifier.width(1.dp).height(52.dp).background(CLine.copy(alpha = 0.75f)))
            DailyMetric(
                modifier = Modifier.weight(1f),
                label = "Consum",
                number = decimalNumber(data?.energyLoadToday, 1),
                total = data?.let { "total ${it.energyLoadTotal.roundToInt()} kWh" } ?: "astept date",
                color = CHouse,
                onClick = { onHistoryClick(historyMetric("energy_load_today")) }
            )
        }
    }
}

@Composable
private fun DailyMetric(
    modifier: Modifier,
    label: String,
    number: String,
    total: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = CMuted, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            TrendGlyph(Modifier.size(12.dp), color.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(3.dp))
        MeasurementText(number = number, unit = "kWh", color = color)
        Text(total, color = CMuted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun SystemDetails(data: SolarData?, onHistoryClick: (HistoryMetric) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CPanel,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("Detalii sistem", color = CText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            DetailRow(
                label = "Baterie",
                value = data?.let { String.format(Locale.US, "%.2f V", it.batteryVoltage) } ?: "—",
                supporting = data?.let { batteryStateLabel(it.batteryDisplay) + " · " + signedWatts(it.batteryDisplay) } ?: "Astept date",
                color = data?.let { batteryColor(it.batteryVoltage) } ?: CMuted,
                onClick = { onHistoryClick(historyMetric("battery_voltage")) }
            )
            DetailDivider()
            DetailRow(
                label = "Panouri",
                value = data?.let { "${it.pv.roundToInt()} W" } ?: "—",
                supporting = data?.let { "PV1 ${it.pv1.roundToInt()} W · PV2 ${it.pv2.roundToInt()} W" } ?: "Astept date",
                color = CPv,
                onClick = { onHistoryClick(historyMetric("pv_power")) }
            )
            DetailDivider()
            DetailRow(
                label = "Retea",
                value = data?.let { String.format(Locale.US, "%.1f V", it.gridVoltage) } ?: "—",
                supporting = data?.let { "Import ${((it.gridImport + it.gridCharge).roundToInt())} W" } ?: "Astept date",
                color = CGrid
            )
            DetailDivider()
            DetailRow(
                label = "Invertor",
                value = data?.let { String.format(Locale.US, "%.1f °C", it.inverterTemp) } ?: "—",
                supporting = data?.let { "Consum propriu ${it.inverterLoss.roundToInt()} W" } ?: "Astept date",
                color = CMuted
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    supporting: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(clickModifier)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = CText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(supporting, color = CMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (onClick != null) {
            Spacer(Modifier.width(9.dp))
            TrendGlyph(Modifier.size(15.dp), color.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun DetailDivider(retro: Boolean = false) {
    val chrome = dashboardChrome(retro)
    HorizontalDivider(Modifier.padding(start = 20.dp), color = chrome.line.copy(alpha = 0.55f))
}

@Composable
private fun TrendGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.76f)
            lineTo(size.width * 0.32f, size.height * 0.48f)
            lineTo(size.width * 0.58f, size.height * 0.62f)
            lineTo(size.width, size.height * 0.18f)
        }
        drawPath(path, color, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun HistoryMenuSheet(
    metrics: List<HistoryMetric>,
    retro: Boolean,
    onMetricClick: (HistoryMetric) -> Unit
) {
    val chrome = dashboardChrome(retro)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 28.dp)
    ) {
        Text(
            if (retro) "ISTORIC" else "Istoric",
            color = if (retro) RetroYellow else chrome.text,
            fontFamily = chrome.font,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (retro) 1.sp else 0.sp
        )
        Text(
            "Alege valoarea pe care vrei sa o analizezi.",
            color = chrome.muted,
            fontFamily = chrome.font,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        metrics.forEachIndexed { index, metric ->
            val accent = historyAccent(metric.field, metric.color, retro)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (retro) RetroPanelRaised.copy(alpha = 0.34f) else Color.Transparent)
                    .clickable { onMetricClick(metric) }
                    .padding(horizontal = 10.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (retro) metric.title.uppercase(Locale.getDefault()) else metric.title,
                        color = chrome.text,
                        fontFamily = chrome.font,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(historySubtitle(metric), color = chrome.muted, fontFamily = chrome.font, fontSize = 10.sp)
                }
                TrendGlyph(Modifier.size(18.dp), accent)
            }
            if (index < metrics.lastIndex) DetailDivider(retro)
        }
    }
}

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

private val DashboardHistoryMetrics = listOf(
    HistoryMetric(
        title = "Consum casa",
        field = "output_power",
        unit = "W",
        color = CHouse,
        defaultRange = "1h",
        ranges = listOf("1h", "6h", "24h")
    ),
    HistoryMetric(
        title = "Productie PV",
        field = "pv_power",
        unit = "W",
        color = CPv,
        defaultRange = "24h",
        ranges = listOf("1h", "6h", "24h")
    ),
    HistoryMetric(
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
    ),
    HistoryMetric(
        title = "Energie produsa",
        field = "energy_pv_today",
        unit = "kWh",
        color = CPv,
        defaultRange = "7d",
        ranges = listOf("7d", "30d"),
        chartStyle = ChartStyle.Bar
    ),
    HistoryMetric(
        title = "Energie consumata",
        field = "energy_load_today",
        unit = "kWh",
        color = CHouse,
        defaultRange = "7d",
        ranges = listOf("7d", "30d"),
        chartStyle = ChartStyle.Bar
    )
)

private fun historyMetric(field: String): HistoryMetric =
    DashboardHistoryMetrics.first { it.field == field }

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
private fun RetroSettingsPage(
    dashboardStyle: DashboardStyle,
    settings: AlarmSettings,
    ringtoneTitle: String,
    version: String,
    onDashboardStyleChange: (DashboardStyle) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onTestAlarm: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RetroPageHeader(
            title = "SETARI",
            subtitle = "TEMA · ALARMA · SUNET · APLICATIE",
            statusColor = RetroYellow
        )

        RetroPanelSurface(
            modifier = Modifier.height(88.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            fillContent = true
        ) {
            Text(
                "TEMA DASHBOARD",
                color = RetroText,
                fontFamily = RetroMono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(5.dp))
            DashboardStyleSwitcher(selected = dashboardStyle, onSelected = onDashboardStyleChange)
            Spacer(Modifier.height(3.dp))
            Text(
                "RETRO: INSTRUMENTE INDUSTRIALE · SIMPLE: MATERIAL 3",
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        RetroPanelSurface(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 10.dp),
            fillContent = true,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "ALARMA CONSUM MARE",
                        color = RetroText,
                        fontFamily = RetroMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        if (settings.enabled) "ACTIVA · SERVICE PERMANENT" else "OPRITA",
                        color = if (settings.enabled) RetroSage else RetroMuted,
                        fontFamily = RetroMono,
                        fontSize = 8.sp
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = retroSwitchColors()
                )
            }

            SettingSlider(
                title = "PRAG ALARMA",
                value = settings.thresholdW,
                valueLabel = "${settings.thresholdW} W",
                range = 3000f..6500f,
                step = 100,
                retro = true,
                compact = true,
                onChange = onThresholdChange
            )
            Text(
                "REARMARE LA ${settings.clearThresholdW} W",
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 7.sp
            )
            SettingSlider(
                title = "COOLDOWN",
                value = settings.cooldownS,
                valueLabel = "${settings.cooldownS}s",
                range = 60f..600f,
                step = 30,
                retro = true,
                compact = true,
                onChange = onCooldownChange
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "VIBRATIE",
                        color = RetroText,
                        fontFamily = RetroMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "IMPULS SCURT LA DECLANSAREA ALARMEI",
                        color = RetroMuted,
                        fontFamily = RetroMono,
                        fontSize = 7.sp,
                        maxLines = 1
                    )
                }
                Switch(
                    checked = settings.vibrate,
                    onCheckedChange = onVibrateChange,
                    colors = retroSwitchColors()
                )
            }
        }

        RetroPanelSurface(
            modifier = Modifier.height(92.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            fillContent = true
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SUNET ALARMA",
                        color = RetroText,
                        fontFamily = RetroMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        ringtoneTitle,
                        color = RetroMuted,
                        fontFamily = RetroMono,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RetroMetalButton(
                    modifier = Modifier.weight(1f).height(36.dp),
                    selected = false,
                    accent = RetroYellow,
                    description = "Alege sunet alarma",
                    onClick = onPickRingtone
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("ALEGE SUNET", color = RetroText, fontFamily = RetroMono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                RetroMetalButton(
                    modifier = Modifier.weight(1f).height(36.dp),
                    selected = true,
                    accent = RetroSage,
                    description = "Testeaza alarma",
                    onClick = onTestAlarm
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("TESTEAZA", color = RetroSage, fontFamily = RetroMono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        RetroPanelSurface(
            modifier = Modifier.height(84.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 9.dp),
            fillContent = true
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("APLICATIE", color = RetroText, fontFamily = RetroMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "VERSIUNE $version  ·  VYRA.GO.RO:31443  ·  API 2S",
                        color = RetroMuted,
                        fontFamily = RetroMono,
                        fontSize = 7.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "READ\nONLY",
                    color = RetroSage,
                    fontFamily = RetroMono,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    dashboardStyle: DashboardStyle,
    settings: AlarmSettings,
    ringtoneTitle: String,
    version: String,
    onDashboardStyleChange: (DashboardStyle) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onTestAlarm: () -> Unit
) {
    val retro = dashboardStyle == DashboardStyle.RETRO
    if (retro) {
        RetroSettingsPage(
            dashboardStyle = dashboardStyle,
            settings = settings,
            ringtoneTitle = ringtoneTitle,
            version = version,
            onDashboardStyleChange = onDashboardStyleChange,
            onEnabledChange = onEnabledChange,
            onThresholdChange = onThresholdChange,
            onCooldownChange = onCooldownChange,
            onVibrateChange = onVibrateChange,
            onPickRingtone = onPickRingtone,
            onTestAlarm = onTestAlarm
        )
        return
    }
    val chrome = dashboardChrome(retro)
    Column(
        Modifier
            .fillMaxSize()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = if (retro) 14.dp else 18.dp,
                top = if (retro) 16.dp else 0.dp,
                end = if (retro) 14.dp else 18.dp,
                bottom = 28.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (retro) 14.dp else 16.dp)
    ) {
        if (retro) {
            RetroPageHeader(
                title = "SETARI",
                subtitle = "TEMA · ALARMA · SUNET · APLICATIE",
                statusColor = RetroYellow
            )
        } else {
            Text(
                "Setari",
                color = chrome.text,
                fontFamily = chrome.font,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text("Aspectul si comportamentul aplicatiei.", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
        }

        SettingsGroup(retro) {
            Text("Tema dashboard", color = chrome.text, fontFamily = chrome.font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            DashboardStyleSwitcher(
                selected = dashboardStyle,
                onSelected = onDashboardStyleChange
            )
            Text(
                if (dashboardStyle == DashboardStyle.RETRO) {
                    "Retro: cadran analogic, afisaj segmentat si panou industrial."
                } else {
                    "Simple: interfata moderna, aerisita si accente Material 3."
                },
                color = chrome.muted,
                fontFamily = chrome.font,
                fontSize = 11.sp
            )
        }

        SettingsGroup(retro) {
            Text("Alarma locala ruleaza pe telefon prin foreground service.", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alarma consum mare", color = chrome.text, fontFamily = chrome.font, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.enabled) "Activa - service permanent" else "Oprita",
                        color = if (settings.enabled) (if (retro) RetroSage else CPv) else chrome.muted,
                        fontFamily = chrome.font,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = if (retro) retroSwitchColors() else SwitchDefaults.colors()
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingSlider(
                title = "Prag alarma",
                value = settings.thresholdW,
                valueLabel = "${settings.thresholdW} W",
                range = 3000f..6500f,
                step = 100,
                retro = retro,
                onChange = onThresholdChange
            )
            Text("Rearmare la ${settings.clearThresholdW} W.", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            SettingSlider(
                title = "Cooldown",
                value = settings.cooldownS,
                valueLabel = "${settings.cooldownS}s",
                range = 60f..600f,
                step = 30,
                retro = retro,
                onChange = onCooldownChange
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vibratie", color = chrome.text, fontFamily = chrome.font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Porneste o vibratie scurta cand alarma suna.", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.vibrate,
                    onCheckedChange = onVibrateChange,
                    colors = if (retro) retroSwitchColors() else SwitchDefaults.colors()
                )
            }
        }

        SettingsGroup(retro) {
            Text("Sunet alarma", color = chrome.text, fontFamily = chrome.font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(ringtoneTitle, color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPickRingtone,
                    modifier = Modifier.weight(1f).shadow(if (retro) 4.dp else 0.dp, RoundedCornerShape(12.dp)),
                    colors = if (retro) ButtonDefaults.buttonColors(containerColor = RetroOlive, contentColor = RetroBackground) else ButtonDefaults.buttonColors()
                ) {
                    Text("Alege sunet", fontFamily = chrome.font)
                }
                Button(
                    onClick = onTestAlarm,
                    modifier = Modifier.weight(1f).shadow(if (retro) 4.dp else 0.dp, RoundedCornerShape(12.dp)),
                    colors = if (retro) ButtonDefaults.buttonColors(containerColor = RetroSage, contentColor = RetroBackground) else ButtonDefaults.buttonColors()
                ) {
                    Text("Testeaza", fontFamily = chrome.font)
                }
            }
        }

        SettingsGroup(retro) {
            Text("Aplicatie", color = chrome.text, fontFamily = chrome.font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Versiune $version", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
            Text("Endpoint: vyra.go.ro:31443", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
            Text("Polling alarma: 2s prin API, nu direct invertor.", color = chrome.muted, fontFamily = chrome.font, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsGroup(retro: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (retro) {
        RetroPanelSurface(content = content)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

@Composable
private fun DashboardStyleSwitcher(
    selected: DashboardStyle,
    onSelected: (DashboardStyle) -> Unit
) {
    val retro = selected == DashboardStyle.RETRO
    val chrome = dashboardChrome(retro)
    if (retro) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DashboardStyle.entries.forEach { style ->
                val isSelected = style == selected
                val accent = if (style == DashboardStyle.RETRO) RetroSage else RetroHouseBlue
                RetroMetalButton(
                    modifier = Modifier.weight(1f).height(34.dp),
                    selected = isSelected,
                    accent = accent,
                    description = "Tema ${style.label}${if (isSelected) ", selectata" else ""}",
                    onClick = { onSelected(style) }
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (isSelected) accent else RetroMuted))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            style.label.uppercase(Locale.getDefault()),
                            color = if (isSelected) accent else RetroMuted,
                            fontFamily = RetroMono,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(chrome.raised)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DashboardStyle.entries.forEach { style ->
            val isSelected = style == selected
            val accent = if (style == DashboardStyle.RETRO) Color(0xFFACCC78) else CHouse
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) accent.copy(alpha = 0.17f) else Color.Transparent)
                    .clickable { onSelected(style) }
                    .semantics {
                        contentDescription = "Tema ${style.label}${if (isSelected) ", selectata" else ""}"
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (isSelected) accent else chrome.muted))
                Spacer(Modifier.width(8.dp))
                Text(
                    style.label,
                    color = if (isSelected) accent else chrome.muted,
                    fontFamily = chrome.font,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
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
    retro: Boolean,
    compact: Boolean = false,
    onChange: (Int) -> Unit
) {
    val chrome = dashboardChrome(retro)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = chrome.text, fontFamily = chrome.font, fontSize = if (compact) 10.sp else 15.sp, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, color = if (retro) RetroYellow else CPv, fontFamily = chrome.font, fontSize = if (compact) 10.sp else 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            modifier = if (compact) Modifier.height(34.dp) else Modifier,
            value = value.toFloat(),
            onValueChange = { raw ->
                val rounded = (raw / step).roundToInt() * step
                onChange(rounded.coerceIn(range.start.roundToInt(), range.endInclusive.roundToInt()))
            },
            valueRange = range,
            colors = if (retro) {
                SliderDefaults.colors(
                    thumbColor = RetroYellow,
                    activeTrackColor = RetroSage,
                    inactiveTrackColor = RetroLine,
                    activeTickColor = RetroBackground,
                    inactiveTickColor = RetroOlive
                )
            } else {
                SliderDefaults.colors()
            }
        )
    }
}

@Composable
private fun retroSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = RetroBackground,
    checkedTrackColor = RetroSage,
    checkedBorderColor = RetroSage,
    uncheckedThumbColor = RetroMuted,
    uncheckedTrackColor = RetroPanelRaised,
    uncheckedBorderColor = RetroOlive
)

@Composable
private fun HistorySheet(
    metric: HistoryMetric,
    retro: Boolean,
    embedded: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chrome = dashboardChrome(retro)
    val accent = historyAccent(metric.field, metric.color, retro)
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

    val scrollModifier = if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())
    Column(
        modifier
            .fillMaxWidth()
            .then(scrollModifier)
            .padding(
                start = if (embedded) 0.dp else 18.dp,
                end = if (embedded) 0.dp else 18.dp,
                bottom = if (embedded) 0.dp else 28.dp
            )
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    metric.title.uppercase(Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    color = accent,
                    fontFamily = chrome.font,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    historySubtitle(metric),
                    color = chrome.muted,
                    fontFamily = chrome.font,
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }
        } else {
            Text(
                if (retro) metric.title.uppercase(Locale.getDefault()) else metric.title,
                color = if (retro) accent else chrome.text,
                fontFamily = chrome.font,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                historySubtitle(metric),
                color = chrome.muted,
                fontFamily = chrome.font,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
        RangeSelector(
            ranges = metric.ranges,
            selectedRange = selectedRange,
            color = accent,
            retro = retro,
            compact = compact
        ) { selectedRange = it }
        Spacer(Modifier.height(if (compact) 7.dp else 16.dp))

        when {
            loading -> {
                if (compact) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("SE INCARCA...", color = chrome.muted, fontFamily = chrome.font, fontSize = 11.sp)
                    }
                } else {
                    Text("Se incarca...", color = chrome.muted, fontFamily = chrome.font, fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                }
            }
            error != null -> {
                if (compact) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(error ?: "", color = chrome.danger, fontFamily = chrome.font, fontSize = 11.sp)
                    }
                } else {
                    Text(error ?: "", color = chrome.danger, fontFamily = chrome.font, fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                }
            }
            series == null || series?.points?.isEmpty() == true -> {
                if (compact) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("FARA DATE PENTRU INTERVAL", color = chrome.muted, fontFamily = chrome.font, fontSize = 11.sp)
                    }
                } else {
                    Text("Fara date pentru intervalul ales", color = chrome.muted, fontFamily = chrome.font, fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                }
            }
            else -> {
                val loaded = series
                if (loaded != null) {
                    if (compact) {
                        Column(Modifier.fillMaxWidth().weight(1f)) {
                            HistoryChart(
                                series = loaded,
                                metric = metric,
                                retro = retro,
                                compact = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.height(7.dp))
                            loaded.stats?.let { stats ->
                                HistoryStatsGrid(stats = stats, metric = metric, retro = retro, compact = true)
                            }
                        }
                    } else {
                        HistoryChart(series = loaded, metric = metric, retro = retro)
                        Spacer(Modifier.height(14.dp))
                        loaded.stats?.let { stats ->
                            HistoryStatsGrid(stats = stats, metric = metric, retro = retro)
                        }
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
    retro: Boolean,
    compact: Boolean = false,
    onRangeClick: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEach { range ->
            RangeChip(
                modifier = Modifier.weight(1f),
                label = range,
                selected = range == selectedRange,
                color = color,
                retro = retro,
                compact = compact,
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
    retro: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val chrome = dashboardChrome(retro)
    val bg = if (selected) color.copy(alpha = 0.18f) else chrome.raised
    val border = if (selected) color.copy(alpha = 0.70f) else chrome.line
    Box(
        modifier
            .shadow(if (selected && retro) 4.dp else 0.dp, RoundedCornerShape(999.dp), clip = false)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) color else chrome.muted,
            fontFamily = chrome.font,
            fontSize = if (compact) 10.sp else 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HistoryChart(
    series: HistorySeries,
    metric: HistoryMetric,
    retro: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (metric.chartStyle == ChartStyle.Bar) {
        BarHistoryChart(
            series = series,
            metric = metric,
            retro = retro,
            compact = compact,
            modifier = modifier
        )
        return
    }

    val chrome = dashboardChrome(retro)
    val accent = historyAccent(metric.field, metric.color, retro)
    val values = series.points.map { it.value }
    val axis = lineAxis(metric, values)
    val timeTicks = timeTicks(series)
    val pointTimes = series.points.map { parsePointMillis(it.time) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(axis.title, color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
            Text("${series.points.size} puncte", color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
        }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        val chartModifier = if (compact) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(190.dp)
        }
        Box(
            chartModifier
                .shadow(if (retro) 7.dp else 0.dp, RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(chrome.background)
                .border(1.dp, chrome.line, RoundedCornerShape(14.dp))
        ) {
            Canvas(Modifier.matchParentSize().padding(if (compact) 7.dp else 10.dp)) {
                val leftPad = 46f * density
                val rightPad = 7f * density
                val topPad = 16f * density
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
                    color = chrome.muted.toArgb()
                    textSize = 10f * density
                    textAlign = Paint.Align.LEFT
                }
                val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = chrome.muted.toArgb()
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
                        color = chrome.line.copy(alpha = 0.55f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1.2f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        formatAxisValue(value, metric.unit),
                        17f * density,
                        y - 4f,
                        yPaint
                    )
                }

                if (firstMs != null && lastMs != null && lastMs > firstMs) {
                    timeTicks.forEach { tick ->
                        val normalized = ((tick.timeMs - firstMs).toDouble() / (lastMs - firstMs).toDouble()).toFloat()
                        val x = plotLeft + normalized.coerceIn(0f, 1f) * plotWidth
                        drawLine(
                            color = chrome.line.copy(alpha = 0.24f),
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
                            color = (if (retro) RetroRed else threshold.color).copy(alpha = 0.70f),
                            start = Offset(plotLeft, y),
                            end = Offset(plotRight, y),
                            strokeWidth = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                        )
                    }
                }

                if (series.points.size == 1) {
                    drawCircle(accent, radius = 5f, center = Offset(plotLeft + plotWidth / 2f, yFor(values.first())))
                } else {
                    val path = Path()
                    series.points.forEachIndexed { index, point ->
                        val x = xFor(index)
                        val y = yFor(point.value)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = accent,
                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                    )
                    val last = series.points.last()
                    drawCircle(
                        color = accent,
                        radius = 5f,
                        center = Offset(xFor(series.points.lastIndex), yFor(last.value))
                    )
                }
            }
            if (retro) {
                RetroReliefEdges(Modifier.matchParentSize(), RoundedCornerShape(14.dp), subtle = true)
                RetroCornerScrews(Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun BarHistoryChart(
    series: HistorySeries,
    metric: HistoryMetric,
    retro: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chrome = dashboardChrome(retro)
    val accent = historyAccent(metric.field, metric.color, retro)
    val values = series.points.map { it.value.coerceAtLeast(0.0) }
    val maxValue = max(values.maxOrNull() ?: 1.0, 1.0)

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatHistoryValue(maxValue, metric.unit), color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
            Text("${series.points.size} zile", color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
        }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        val chartModifier = if (compact) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(190.dp)
        }
        Box(
            chartModifier
                .shadow(if (retro) 7.dp else 0.dp, RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(chrome.background)
                .border(1.dp, chrome.line, RoundedCornerShape(14.dp))
        ) {
            Canvas(Modifier.matchParentSize().padding(if (compact) 7.dp else 10.dp)) {
                val width = size.width
                val height = size.height
                for (i in 0..3) {
                    val y = height * i / 3f
                    drawLine(
                        color = chrome.line.copy(alpha = 0.55f),
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
                            color = accent,
                            start = Offset(x, height),
                            end = Offset(x, y),
                            strokeWidth = barWidth,
                            cap = StrokeCap.Butt
                        )
                    }
                }
            }
            if (retro) {
                RetroReliefEdges(Modifier.matchParentSize(), RoundedCornerShape(14.dp), subtle = true)
                RetroCornerScrews(Modifier.matchParentSize())
            }
        }
        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
        Text("0 ${metric.unit}", color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
    }
}

@Composable
private fun HistoryStatsGrid(
    stats: HistoryStats,
    metric: HistoryMetric,
    retro: Boolean,
    compact: Boolean = false
) {
    val accent = historyAccent(metric.field, metric.color, retro)
    if (compact) {
        val compactValues = if (metric.chartStyle == ChartStyle.Bar) {
            listOf(
                "TOTAL" to stats.sum,
                "MEDIE" to stats.avg,
                "MAX" to stats.max,
                "ULTIM" to stats.last
            )
        } else {
            listOf(
                "ULTIM" to stats.last,
                "MIN" to stats.min,
                "MEDIE" to stats.avg,
                (if (metric.unit == "W") "VARF" else "MAX") to stats.max
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            compactValues.forEach { (label, rawValue) ->
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = label,
                    value = formatHistoryValue(rawValue, metric.unit),
                    color = accent,
                    retro = retro,
                    compact = true
                )
            }
        }
        return
    }
    if (metric.chartStyle == ChartStyle.Bar) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Modifier.weight(1f), "Total", formatHistoryValue(stats.sum, metric.unit), accent, retro)
                StatTile(Modifier.weight(1f), "Medie/zi", formatHistoryValue(stats.avg, metric.unit), accent, retro)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Modifier.weight(1f), "Max zi", formatHistoryValue(stats.max, metric.unit), accent, retro)
                StatTile(Modifier.weight(1f), "Ultima zi", formatHistoryValue(stats.last, metric.unit), accent, retro)
            }
        }
        return
    }

    val maxLabel = if (metric.unit == "W") "Varf" else "Max"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), "Ultim", formatHistoryValue(stats.last, metric.unit), accent, retro)
            StatTile(Modifier.weight(1f), "Min", formatHistoryValue(stats.min, metric.unit), accent, retro)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), "Medie", formatHistoryValue(stats.avg, metric.unit), accent, retro)
            StatTile(Modifier.weight(1f), maxLabel, formatHistoryValue(stats.max, metric.unit), accent, retro)
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color,
    retro: Boolean,
    compact: Boolean = false
) {
    val chrome = dashboardChrome(retro)
    Box(
        modifier
            .shadow(if (retro) 6.dp else 0.dp, RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .background(chrome.raised)
            .then(
                if (retro) Modifier.border(1.dp, RetroOlive.copy(alpha = 0.52f), RoundedCornerShape(12.dp))
                else Modifier
            )
        ) {
        Column(
            Modifier.padding(
                horizontal = if (compact) 7.dp else 20.dp,
                vertical = if (compact) 5.dp else 16.dp
            )
        ) {
            Text(
                label,
                color = chrome.muted,
                fontFamily = chrome.font,
                fontSize = if (compact) 7.sp else 12.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
            Text(
                value,
                color = color,
                fontFamily = chrome.font,
                fontSize = if (compact) 10.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (retro) RetroReliefEdges(Modifier.matchParentSize(), RoundedCornerShape(12.dp), subtle = true)
        if (retro) {
            if (compact) RetroMiniScrews(Modifier.matchParentSize()) else RetroCornerScrews(Modifier.matchParentSize())
        }
    }
}

private fun watts(value: Double): String = "${value.roundToInt()} W"

private fun wholeNumber(value: Double?): String = value?.roundToInt()?.toString() ?: "—"

private fun signedNumber(value: Double?): String {
    val rounded = value?.roundToInt() ?: return "—"
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun decimalNumber(value: Double?, decimals: Int): String =
    value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "—"

private fun batteryStateLabel(power: Double): String = when {
    power > DEAD -> "Incarcare"
    power < -DEAD -> "Descarcare"
    else -> "Standby"
}

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
    "kWh" -> String.format(Locale.US, "%.1fkWh", value)
    else -> String.format(Locale.US, "%.1f%s", value, unit)
}

private fun formatHistoryValue(value: Double, unit: String): String = when (unit) {
    "V" -> String.format(Locale.US, "%.2f V", value)
    "W" -> "${value.roundToInt()} W"
    "kWh" -> String.format(Locale.US, "%.1f kWh", value)
    else -> String.format(Locale.US, "%.1f %s", value, unit)
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

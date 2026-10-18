package com.rolling7.solar

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import androidx.core.view.WindowCompat
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

// Paleta temei Simple - "high-tech" pe gri rece, deliberat diferita de Retro.
// Fundalul e gri (nu alb) ca sa nu oboseasca ochiul; cardurile stau cu o treapta
// mai sus decat pagina, de acolo vine senzatia de contur fara sa desenam borduri.
// Accentele sunt mai inchise decat versiunea pe fond negru: un verde sau un
// albastru luminos ar avea contrast prea mic pe gri deschis.
private val CPv = Color(0xFF1F9D55)          // verde panouri
private val CBat = Color(0xFFC2790A)         // ambru baterie
private val CGrid = Color(0xFFD93A48)        // rosu retea
private val CHouse = Color(0xFF0B72E7)       // albastru accent / casa
private val CPanel = Color(0xFFF5F8FB)       // suprafata card
private val CPanelSoft = Color(0xFFEAF0F7)   // card secundar / camp
private val CPanelRaised = Color(0xFFFFFFFF) // card evidentiat
private val CBg = Color(0xFFDDE3EA)          // fundal pagina
private val CLine = Color(0xFFC9D3DE)        // separator
private val CMuted = Color(0xFF586576)       // text secundar
private val CText = Color(0xFF1B2432)        // text principal
private const val DEAD = 50.0

// Cadranul din tema Simple. Pragurile sunt fixe, nu legate de alarma din setari:
// invertorul se protejeaza pe la ~6.6 kW, iar scala e citita ca o limita a aparatului.
private const val GAUGE_MAX_W = 7000f
private const val GAUGE_WARN_W = 5500f
private const val GAUGE_DANGER_W = 6000f
private val CGaugeWarn = Color(0xFFE9B209)   // galben avertizare

// Umbra folosita pe toate valorile mari din tema Simple: le ridica de pe card.
private val ValueShadow = Shadow(
    color = Color(0xFF16324F).copy(alpha = 0.30f),
    offset = Offset(0f, 3f),
    blurRadius = 7f
)

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
    var showHistoryMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var retroTabName by rememberSaveable { mutableStateOf(RetroTab.DASHBOARD.name) }
    var selectedEnergyField by rememberSaveable { mutableStateOf("energy_pv_today") }
    // Intervalul ramane acelasi cand schimbi graficul si e comun ambelor teme.
    var energyRange by rememberSaveable { mutableStateOf(RetroEnergyDefaultRange) }
    var alarmSettings by remember { mutableStateOf(AlarmSettingsStore.read(context)) }
    var dashboardStyle by remember { mutableStateOf(DashboardStyleStore.read(context)) }
    var enableAfterNotificationPermission by remember { mutableStateOf(false) }
    val alarmRinging by AlarmState.ringing.collectAsState()
    val alarmMessage by AlarmState.message.collectAsState()
    val retro = dashboardStyle == DashboardStyle.RETRO
    val chrome = dashboardChrome(retro)
    val view = LocalView.current

    // Tema Simple e deschisa la culoare, Retro e inchisa. Bara de stare si cea de
    // navigare trebuie sa urmeze fundalul temei, altfel raman negre peste un ecran
    // luminos. `isAppearanceLight*` intoarce iconitele de sistem pe inchis.
    LaunchedEffect(retro) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        window.statusBarColor = chrome.background.toArgb()
        window.navigationBarColor = chrome.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !retro
            isAppearanceLightNavigationBars = !retro
        }
    }

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

    // Retro e tema inchisa, Simple e deschisa - schema Material trebuie sa fie a
    // fiecareia, altfel dialogurile si meniurile Material raman pe culorile celeilalte.
    val colors = if (retro) {
        darkColorScheme(
            primary = RetroSage,
            secondary = RetroYellow,
            error = chrome.danger,
            background = chrome.background,
            surface = chrome.panel,
            surfaceVariant = chrome.raised,
            onSurface = chrome.text,
            onBackground = chrome.text
        )
    } else {
        lightColorScheme(
            primary = CHouse,
            secondary = CPv,
            error = chrome.danger,
            background = chrome.background,
            surface = chrome.panel,
            surfaceVariant = chrome.raised,
            onSurface = chrome.text,
            onBackground = chrome.text,
            // `tonalElevation` de pe Surface amesteca `surfaceTint` in culoarea cardului.
            // Implicit tinta e `primary`, ceea ce pe fundal deschis spala cardurile in
            // culoarea accentului. `Color.Transparent` NU rezolva: fiind 0x00000000, M3 ii
            // pune alpha-ul de elevatie si compune negru peste card, deci il inchide.
            // Corect e sa tintim chiar culoarea suprafetei - compunerea devine neutra.
            surfaceTint = chrome.panel
        )
    }

    MaterialTheme(colorScheme = colors) {
        val simpleEnergyContent: @Composable () -> Unit = {
            SimpleEnergyPage(
                data = data,
                selectedField = normalizedEnergyField(selectedEnergyField),
                selectedRange = normalizedRetroEnergyRange(energyRange),
                onFieldSelected = { selectedEnergyField = it },
                onRangeSelected = { energyRange = it }
            )
        }
        
        val simpleSystemContent: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SystemDetails(data = data, onHistoryClick = {
                    selectedEnergyField = it.field
                    retroTabName = RetroTab.ENERGY.name
                })
                SimpleServerMetrics(data = data)
            }
        }
        
        val simpleSettingsContent: @Composable () -> Unit = {
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
                        selectedField = normalizedEnergyField(selectedEnergyField),
                        selectedRange = normalizedRetroEnergyRange(energyRange),
                        onFieldSelected = { selectedEnergyField = it },
                        onRangeSelected = { energyRange = it }
                    )
                },
                settingsContent = simpleSettingsContent
            )
            DashboardStyle.SIMPLE -> SimpleDashboard(
                data = data,
                selectedTab = RetroTab.valueOf(retroTabName),
                onTabSelected = { tab -> retroTabName = tab.name },
                onHistoryFieldClick = {
                    selectedEnergyField = it.field
                    retroTabName = RetroTab.ENERGY.name
                },
                energyContent = simpleEnergyContent,
                systemContent = simpleSystemContent,
                settingsContent = simpleSettingsContent
            )
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

/** Istoricul graficului ales; reincarcat la schimbarea campului, a intervalului sau la reincercare. */
private class EnergyHistoryState {
    var series by mutableStateOf<HistorySeries?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun rememberEnergyHistory(field: String, range: String, reloadKey: Int, errorText: String): EnergyHistoryState {
    val state = remember(field, range, reloadKey) { EnergyHistoryState() }
    LaunchedEffect(field, range, reloadKey) {
        state.loading = true
        state.error = null
        state.series = null
        val result = withContext(Dispatchers.IO) { SolarRepository.fetchHistory(field, range) }
        if (result == null) state.error = errorText else state.series = result
        state.loading = false
    }
    return state
}

@Composable
private fun RetroEnergyPage(
    data: SolarData?,
    selectedField: String,
    selectedRange: String,
    onFieldSelected: (String) -> Unit,
    onRangeSelected: (String) -> Unit
) {
    val metric = historyMetric(selectedField)
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    val history = rememberEnergyHistory(selectedField, selectedRange, reloadKey, "ISTORIC INDISPONIBIL")
    val series = history.series

    RetroEnergyArtworkPage(
        data = data,
        selectedField = selectedField,
        selectedRange = selectedRange,
        chartTitle = energyChartTitle(selectedField, selectedRange),
        spanLabel = series?.let { energySeriesSpan(it, metric) } ?: if (history.loading) "SE INCARCA..." else "—",
        stats = energyStatPairs(series?.stats, metric),
        onFieldClick = onFieldSelected,
        onRangeClick = { onRangeSelected(normalizedRetroEnergyRange(it, selectedField)) },
        chartContent = { modifier ->
            RetroEnergyEmbeddedHistoryChart(
                metric = metric,
                series = series,
                loading = history.loading,
                error = history.error,
                onRetry = { reloadKey += 1 },
                modifier = modifier
            )
        }
    )
}

/**
 * ENERGIE in tema Simple, aceeasi ordine ca in Retro: energia de azi (informativ), CE urmaresti,
 * CUM, CAT timp, apoi graficul cu cele patru cifre si cat de proaspete sunt datele.
 */
@Composable
private fun SimpleEnergyPage(
    data: SolarData?,
    selectedField: String,
    selectedRange: String,
    onFieldSelected: (String) -> Unit,
    onRangeSelected: (String) -> Unit
) {
    val subject = energySubjectForField(selectedField)
    val mode = energyModeForField(selectedField)
    val freshness = energyFreshness(data?.timestamp, rememberNowMillis())
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimpleTodayTile(Modifier.weight(1f), "Produs azi", data?.energyPvToday, CPv)
            SimpleTodayTile(Modifier.weight(1f), "Consumat azi", data?.energyLoadToday, CHouse)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnergySubject.values().forEach { option ->
                RangeChip(
                    modifier = Modifier.weight(1f),
                    label = option.label,
                    selected = option == subject,
                    color = simpleSubjectColor(option),
                    retro = false,
                    onClick = { onFieldSelected(energyFieldForSubject(option, selectedField)) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            energyModesFor(subject).forEach { option ->
                RangeChip(
                    modifier = Modifier.weight(1f),
                    label = "${option.label} · ${option.unit}",
                    selected = option == mode,
                    color = simpleSubjectColor(subject),
                    retro = false,
                    compact = true,
                    onClick = { onFieldSelected(energyFieldFor(subject, option)) }
                )
            }
        }
        HistorySheet(
            metric = historyMetric(selectedField),
            retro = false,
            selectedRange = selectedRange,
            onRangeChange = onRangeSelected,
            title = energyChartTitle(selectedField, selectedRange),
            embedded = true,
            compact = true,
            modifier = Modifier.weight(1f)
        )
        Text(
            freshness.text.lowercase(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) },
            color = if (freshness.stale) CGrid else CMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SimpleTodayTile(modifier: Modifier, label: String, value: Double?, color: Color) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CPanel)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Text(label, color = CMuted, fontSize = 11.sp, maxLines = 1)
        Text(
            value?.let { String.format(Locale.US, "%.1f kWh", it) } ?: "—",
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private fun simpleSubjectColor(subject: EnergySubject): Color = when (subject) {
    EnergySubject.HOUSE -> CHouse
    EnergySubject.PANELS -> CPv
    EnergySubject.BATTERY -> CBat
}

private fun energySeriesSpan(series: HistorySeries, metric: HistoryMetric): String =
    energySpanLabel(
        times = series.points.map { it.time },
        window = series.window,
        range = series.range,
        bars = metric.chartStyle == ChartStyle.Bar,
        zone = LocalZone
    )

/**
 * Cele patru cifre de sub grafic, aceleasi in ambele teme. Pentru bare: total, medie pe bara, maximul
 * unei bare, ultima bara. Pentru linii: valorile vin din datele brute (API), nu din punctele graficului.
 */
private fun energyStatPairs(stats: HistoryStats?, metric: HistoryMetric): List<Pair<String, String>> {
    val labels = if (metric.chartStyle == ChartStyle.Bar) {
        listOf("TOTAL", "MEDIE", "MAX", "ULTIM")
    } else {
        listOf("ULTIM", "MIN", "MEDIE", if (metric.unit == "W") "VARF" else "MAX")
    }
    if (stats == null) return labels.map { it to "—" }
    val values = if (metric.chartStyle == ChartStyle.Bar) {
        listOf(stats.sum, stats.avg, stats.max, stats.last)
    } else {
        listOf(stats.last, stats.min, stats.avg, stats.max)
    }
    return labels.zip(values) { label, value -> label to formatHistoryValue(value, metric.unit) }
}

@Composable
private fun RetroEnergyEmbeddedHistoryChart(
    metric: HistoryMetric,
    series: HistorySeries?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = historyAccent(metric.field, metric.color, retro = true)
    Box(
        modifier = modifier.semantics {
            contentDescription = when {
                loading -> "Grafic ${metric.title}, se incarca"
                error != null -> "Grafic ${metric.title}, eroare. Apasa pentru reincercare"
                series?.points.isNullOrEmpty() -> "Grafic ${metric.title}, fara date"
                else -> "Grafic ${metric.title}, ${series?.points?.size ?: 0} puncte"
            }
        },
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> RetroEnergyChartMessage(
                text = "SE INCARCA...",
                color = accent
            )

            error != null -> RetroEnergyChartMessage(
                text = "$error\nAPASA PENTRU REINCERCARE",
                color = RetroRed,
                modifier = Modifier.clickable(onClick = onRetry)
            )

            series == null || series.points.isEmpty() -> RetroEnergyChartMessage(
                text = "NU EXISTA DATE PENTRU ACEST INTERVAL",
                color = RetroMuted
            )

            else -> RetroEnergyChartCanvas(
                series = series,
                metric = metric,
                accent = accent,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RetroEnergyChartMessage(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontFamily = RetroMono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 16.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(18.dp)
    )
}

@Composable
private fun RetroEnergyChartCanvas(
    series: HistorySeries,
    metric: HistoryMetric,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val values = series.points.map { point ->
        if (metric.chartStyle == ChartStyle.Bar) point.value.coerceAtLeast(0.0) else point.value
    }
    val axis = if (metric.chartStyle == ChartStyle.Bar) {
        val maxValue = max(values.maxOrNull() ?: 0.1, 0.1)
        val step = niceStep(maxValue / 4.0)
        val top = max(step * 4.0, ceil(maxValue / step) * step)
        LineAxis(
            min = 0.0,
            max = top,
            gridValues = (4 downTo 0).map { top * it / 4.0 },
            title = "0-${formatAxisValue(top, metric.unit)}"
        )
    } else {
        lineAxis(metric, values)
    }
    val tickIndices = energyTickIndices(series.points.map { it.time }, series.range, LocalZone)

    Canvas(modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
        val leftPad = 45.dp.toPx()
        // Tinem ultima data departe de surubul fotografic din coltul ramei.
        val rightPad = 24.dp.toPx()
        val topPad = 8.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RetroMuted.copy(alpha = 0.92f).toArgb()
            textSize = 8.5.sp.toPx()
            textAlign = Paint.Align.LEFT
        }
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RetroMuted.copy(alpha = 0.92f).toArgb()
            textSize = 8.sp.toPx()
            textAlign = Paint.Align.CENTER
        }

        fun yFor(value: Double): Float {
            val span = (axis.max - axis.min).coerceAtLeast(0.0001)
            val normalized = ((value - axis.min) / span).toFloat().coerceIn(0f, 1f)
            return plotBottom - normalized * plotHeight
        }

        fun xFor(index: Int): Float {
            if (series.points.size <= 1) return plotLeft + plotWidth / 2f
            return plotLeft + plotWidth * index / series.points.lastIndex.toFloat()
        }

        axis.gridValues.forEach { value ->
            val y = yFor(value)
            drawLine(
                color = RetroOlive.copy(alpha = 0.30f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx()
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatAxisValue(value, metric.unit),
                1.dp.toPx(),
                y - 2.dp.toPx(),
                yPaint
            )
        }

        // Barele stau in centrul slotului lor, liniile pe pozitia punctului.
        val barSlot = plotWidth / series.points.size.coerceAtLeast(1)
        fun pointX(index: Int): Float =
            if (metric.chartStyle == ChartStyle.Bar) plotLeft + barSlot * index + barSlot / 2f else xFor(index)

        // Etichete doar pe ore rotunde; una care s-ar suprapune cu precedenta sau ar iesi din
        // cadru e sarita.
        var lastLabelRight = Float.NEGATIVE_INFINITY
        tickIndices.forEach { index ->
            val x = pointX(index)
            val label = energyTickLabel(series.points[index].time, series.range, LocalZone)
            val half = xPaint.measureText(label) / 2f
            if (x - half < lastLabelRight + 4.dp.toPx() || x + half > size.width) return@forEach
            drawLine(
                color = RetroOlive.copy(alpha = 0.15f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 0.8.dp.toPx()
            )
            drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 3.dp.toPx(), xPaint)
            lastLabelRight = x + half
        }

        metric.thresholds.forEach { threshold ->
            if (threshold.value in axis.min..axis.max) {
                val y = yFor(threshold.value)
                drawLine(
                    color = RetroRed.copy(alpha = 0.76f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1.3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                    )
                )
            }
        }

        if (metric.chartStyle == ChartStyle.Bar) {
            val slot = plotWidth / values.size.coerceAtLeast(1)
            val barWidth = (slot * 0.62f).coerceAtLeast(2.dp.toPx())
            values.forEachIndexed { index, value ->
                val x = plotLeft + slot * index + slot / 2f
                val top = yFor(value)
                drawLine(
                    color = accent.copy(alpha = 0.20f),
                    start = Offset(x, plotBottom),
                    end = Offset(x, top),
                    strokeWidth = (barWidth + 3.dp.toPx()).coerceAtMost(slot * 0.90f),
                    cap = StrokeCap.Butt
                )
                drawLine(
                    color = accent.copy(alpha = 0.92f),
                    start = Offset(x, plotBottom),
                    end = Offset(x, top),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Butt
                )
            }
        } else {
            val line = Path()
            val fill = Path()
            series.points.forEachIndexed { index, point ->
                val x = xFor(index)
                val y = yFor(point.value)
                if (index == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, plotBottom)
                    fill.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            fill.lineTo(xFor(series.points.lastIndex), plotBottom)
            fill.close()
            drawPath(fill, color = accent.copy(alpha = 0.08f))
            drawPath(
                path = line,
                color = accent.copy(alpha = 0.22f),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                path = line,
                color = accent,
                style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
            )
            drawCircle(
                color = accent.copy(alpha = 0.25f),
                radius = 5.dp.toPx(),
                center = Offset(xFor(series.points.lastIndex), yFor(values.last()))
            )
            drawCircle(
                color = accent,
                radius = 2.2.dp.toPx(),
                center = Offset(xFor(series.points.lastIndex), yFor(values.last()))
            )
        }
    }
}

private fun barCountLabel(series: HistorySeries): String {
    val count = series.points.size
    return when (series.range) {
        "2h" -> "$count x 10 min"
        "1d", "24h" -> "$count ore"
        else -> "$count zile"
    }
}

/**
 * O citire "gravata" in placa: adancitura are umbra sus si lumina jos, iar cifra e desenata
 * de doua ori - o copie deschisa decalata in jos-dreapta, apoi textul propriu-zis peste ea.
 * Asa arata sapat in material, nu lipit deasupra.
 */
@Composable
private fun EngravedReadout(
    iconRes: Int,
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(74.dp)
            .semantics { contentDescription = "$label $value $unit" }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val corner = CornerRadius(13.dp.toPx())
            // Fundul adanciturii, cu un gradient care il face sa para scobit.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFD5DDE7), Color(0xFFE9EEF4), Color(0xFFF2F6FA))
                ),
                cornerRadius = corner
            )
            // Muchia de sus e in umbra, cea de jos prinde lumina: asta face adancitura.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF63758A).copy(alpha = 0.70f),
                        Color(0xFF63758A).copy(alpha = 0.06f),
                        Color.White
                    )
                ),
                cornerRadius = corner,
                style = Stroke(width = 3.5.dp.toPx())
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = CMuted.copy(alpha = 0.75f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    label,
                    color = CMuted.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    Text(
                        value,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(x = 1.dp, y = 1.5.dp)
                    )
                    Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    " $unit",
                    color = CMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SimpleGauge(
    powerW: Double,
    modifier: Modifier = Modifier
) {
    val animatedPower by animateFloatAsState(
        targetValue = powerW.toFloat().coerceIn(0f, GAUGE_MAX_W),
        animationSpec = tween(700),
        label = "GaugeAngle"
    )
    val startAngle = 150f
    val sweepAngle = 240f

    // Fata cadranului e un disc intreg, nu doar sectorul arcului, deci cutia trebuie sa
    // incapa un cerc complet: raza se limiteaza si pe latime si pe inaltime.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Consum casa ${powerW.roundToInt()} wati din maximum ${GAUGE_MAX_W.roundToInt()}"
            },
        contentAlignment = Alignment.Center
    ) {
        val gaugeRadius = (kotlin.math.min(maxWidth.value, maxHeight.value) / 2f - 10f).dp
        // Valoarea trebuie sa se micsoreze odata cu cadranul. Cu font fix, pe un ecran mai
        // scurt (Note 9) cifrele ajung peste etichetele "0" si "7".
        val valueSp = (gaugeRadius.value * 0.36f).coerceIn(24f, 44f).sp
        val unitSp = (valueSp.value * 0.45f).sp

        Canvas(Modifier.fillMaxSize()) {
            val radius = gaugeRadius.toPx()
            val centerOffset = Offset(size.width / 2f, size.height / 2f)
            val ringWidth = 12.dp.toPx()

            fun angleOf(watts: Float) = startAngle + (watts / GAUGE_MAX_W) * sweepAngle
            fun arcBox(r: Float) = Offset(centerOffset.x - r, centerOffset.y - r)

            // Fata cadranului: gradient radial deschis in centru, mai inchis spre margine.
            // De aici vine senzatia de suprafata bombata.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, CPanelRaised, Color(0xFFE7ECF2)),
                    center = Offset(centerOffset.x - radius * 0.22f, centerOffset.y - radius * 0.26f),
                    radius = radius * 1.15f
                ),
                radius = radius - ringWidth * 0.55f,
                center = centerOffset
            )

            // Umbra proprie a inelului, decalata in jos-dreapta.
            drawArc(
                color = Color(0xFF9AA7B4).copy(alpha = 0.28f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = ringWidth, cap = StrokeCap.Round),
                topLeft = arcBox(radius) + Offset(1.5.dp.toPx(), 2.5.dp.toPx()),
                size = Size(radius * 2, radius * 2)
            )

            // Pista, cu gradient ca sa nu para plata.
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(CHouse.copy(alpha = 0.30f), CHouse.copy(alpha = 0.13f)),
                    start = Offset(centerOffset.x - radius, centerOffset.y - radius),
                    end = Offset(centerOffset.x + radius, centerOffset.y + radius)
                ),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = ringWidth, cap = StrokeCap.Round),
                topLeft = arcBox(radius),
                size = Size(radius * 2, radius * 2)
            )

            // Zonele de avertizare: galben de la 5500 W, rosu de la 6000 W pana la capat.
            fun zone(fromW: Float, toW: Float, color: Color) {
                val from = angleOf(fromW)
                drawArc(
                    color = color,
                    startAngle = from,
                    sweepAngle = angleOf(toW) - from,
                    useCenter = false,
                    style = Stroke(width = ringWidth),
                    topLeft = arcBox(radius),
                    size = Size(radius * 2, radius * 2)
                )
            }
            zone(GAUGE_WARN_W, GAUGE_DANGER_W, CGaugeWarn)
            zone(GAUGE_DANGER_W, GAUGE_MAX_W, CGrid)

            // Gradatii si cifre.
            val tickRadiusOut = radius - ringWidth - 4.dp.toPx()
            val textRadius = radius - ringWidth - radius * 0.22f
            val bigTicks = 7
            val totalTicks = bigTicks * 5

            val textPaint = Paint().apply {
                color = CMuted.toArgb()
                textSize = 13.sp.toPx()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }

            for (i in 0..totalTicks) {
                val angleRad = Math.toRadians((startAngle + i.toFloat() / totalTicks * sweepAngle).toDouble())
                val cos = kotlin.math.cos(angleRad).toFloat()
                val sin = kotlin.math.sin(angleRad).toFloat()
                val isBig = i % 5 == 0
                val tickLen = if (isBig) 11.dp.toPx() else 5.dp.toPx()
                drawLine(
                    color = if (isBig) CText.copy(alpha = 0.55f) else CMuted.copy(alpha = 0.38f),
                    start = Offset(
                        centerOffset.x + (tickRadiusOut - tickLen) * cos,
                        centerOffset.y + (tickRadiusOut - tickLen) * sin
                    ),
                    end = Offset(centerOffset.x + tickRadiusOut * cos, centerOffset.y + tickRadiusOut * sin),
                    strokeWidth = if (isBig) 2.4.dp.toPx() else 1.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                if (isBig) {
                    val tx = centerOffset.x + textRadius * cos
                    val ty = centerOffset.y + textRadius * sin - (textPaint.ascent() + textPaint.descent()) / 2
                    drawContext.canvas.nativeCanvas.drawText((i / 5).toString(), tx, ty, textPaint)
                }
            }

            // Acul: umbra intai, apoi acul cu gradient, ca sa para ridicat de pe cadran.
            val needleRad = Math.toRadians(angleOf(animatedPower).toDouble())
            val nCos = kotlin.math.cos(needleRad).toFloat()
            val nSin = kotlin.math.sin(needleRad).toFloat()
            val needleLen = radius * 0.70f
            val baseHalf = 5.5.dp.toPx()

            fun needlePath(shift: Offset): Path {
                val c = centerOffset + shift
                val tip = Offset(c.x + needleLen * nCos, c.y + needleLen * nSin)
                val left = Offset(c.x + baseHalf * (-nSin), c.y + baseHalf * nCos)
                val right = Offset(c.x - baseHalf * (-nSin), c.y - baseHalf * nCos)
                return Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                }
            }

            drawPath(needlePath(Offset(2.dp.toPx(), 3.dp.toPx())), color = Color(0xFF16324F).copy(alpha = 0.22f))
            drawPath(
                path = needlePath(Offset.Zero),
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF2F93FF), CHouse, Color(0xFF0A4FA5)),
                    start = Offset(centerOffset.x - baseHalf * 2, centerOffset.y - baseHalf * 2),
                    end = Offset(centerOffset.x + needleLen * nCos, centerOffset.y + needleLen * nSin)
                )
            )

            // Butucul central, tot cu umbra si gradient.
            drawCircle(
                color = Color(0xFF16324F).copy(alpha = 0.20f),
                radius = 10.dp.toPx(),
                center = centerOffset + Offset(1.5.dp.toPx(), 2.5.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF57ABFF), CHouse, Color(0xFF0A4FA5)),
                    center = centerOffset - Offset(3.dp.toPx(), 3.dp.toPx()),
                    radius = 14.dp.toPx()
                ),
                radius = 9.5.dp.toPx(),
                center = centerOffset
            )
            drawCircle(color = CPanelRaised, radius = 3.4.dp.toPx(), center = centerOffset)
        }

        // Valoarea sta in golul de jos al arcului, sub butuc.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = gaugeRadius * 0.60f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = powerW.roundToInt().toString(),
                    color = CText,
                    fontSize = valueSp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(shadow = ValueShadow)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "W",
                    color = CHouse,
                    fontSize = unitSp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = (valueSp.value * 0.15f).dp)
                )
            }
        }
    }
}

@Composable
private fun SimpleDashboard(
    data: SolarData?,
    selectedTab: RetroTab,
    onTabSelected: (RetroTab) -> Unit,
    onHistoryFieldClick: (HistoryMetric) -> Unit,
    energyContent: @Composable () -> Unit,
    systemContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = CBg) {
        Column(Modifier.fillMaxSize()) {
            val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .retroSwipeNavigation(
                        selectedTab = selectedTab,
                        threshold = swipeThreshold,
                        onTabSelected = onTabSelected
                    )
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enterOffset: (Int) -> Int = { width -> if (forward) width else -width }
                        val exitOffset: (Int) -> Int = { width -> if (forward) -width else width }
                        (
                            androidx.compose.animation.slideInHorizontally(
                                animationSpec = tween(durationMillis = 240),
                                initialOffsetX = enterOffset
                            ) + androidx.compose.animation.fadeIn(animationSpec = tween(durationMillis = 180))
                        ).togetherWith(
                            androidx.compose.animation.slideOutHorizontally(
                                animationSpec = tween(durationMillis = 240),
                                targetOffsetX = exitOffset
                            ) + androidx.compose.animation.fadeOut(animationSpec = tween(durationMillis = 180))
                        )
                    },
                    label = "Navigare pagini Simple",
                    modifier = Modifier.fillMaxSize()
                ) { visibleTab ->
                    when (visibleTab) {
                        RetroTab.DASHBOARD -> {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // 1. Rand de antet compact
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val context = LocalContext.current
                                    Text(
                                        text = "V${appVersion(context)}",
                                        color = CMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val source = sourceLabel(data)
                                    val sourceStatus = if (data == null) "SE CONECTEAZA" else "CASA DIN ${source.uppercase(Locale.getDefault())}"
                                    StatusPill(label = sourceStatus, color = sourceColor(data))
                                }

                                // 2. Card CONSUM CASA
                                Surface(
                                    modifier = Modifier.fillMaxWidth().weight(0.54f),
                                    shape = RoundedCornerShape(20.dp),
                                    color = CPanel,
                                    tonalElevation = 2.dp
                                ) {
                                    // Panourile stau in acelasi card cu cadranul: consumul si
                                    // productia se citesc dintr-o privire, si castigam un card.
                                    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                                        SimpleGauge(
                                            powerW = data?.house ?: 0.0,
                                            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 150.dp)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        HorizontalDivider(color = CLine.copy(alpha = 0.55f))
                                        Spacer(Modifier.height(10.dp))
                                        Row(Modifier.fillMaxWidth()) {
                                            val pv1 = data?.pv1?.roundToInt() ?: 0
                                            val pv2 = data?.pv2?.roundToInt() ?: 0
                                            val pvTotal = data?.pv?.roundToInt() ?: 0
                                            
                                            @Composable
                                            fun PvCol(label: String, value: Int, modifier: Modifier) {
                                                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(label, color = CMuted, fontSize = 10.sp)
                                                    Spacer(Modifier.height(2.dp))
                                                    Row(verticalAlignment = Alignment.Bottom) {
                                                        Text(
                                                            value.toString(),
                                                            color = CHouse,
                                                            fontSize = 23.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            style = LocalTextStyle.current.copy(shadow = ValueShadow)
                                                        )
                                                        Text(" W", color = CMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
                                                    }
                                                }
                                            }
                                            
                                            PvCol("PV1", pv1, Modifier.weight(1f))
                                            Box(Modifier.width(1.dp).height(32.dp).background(CLine.copy(alpha = 0.5f)))
                                            PvCol("PV2", pv2, Modifier.weight(1f))
                                            Box(Modifier.width(1.dp).height(32.dp).background(CLine.copy(alpha = 0.5f)))
                                            PvCol("TOTAL", pvTotal, Modifier.weight(1f))
                                        }
                                    }
                                }

                                // 3. Card FLUX ENERGETIC. Fara titlu: spatiul castigat merge in
                                // inaltimea diagramei, ca panoul sa stea vizibil mai sus decat
                                // casa si sa se citeasca sensul curgerii.
                                Surface(
                                    modifier = Modifier.fillMaxWidth().weight(0.46f),
                                    shape = RoundedCornerShape(16.dp),
                                    color = CPanel,
                                    tonalElevation = 1.dp
                                ) {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        EnergyFlow(
                                            data = data,
                                            modifier = Modifier.fillMaxSize().heightIn(min = 170.dp),
                                            onHistoryClick = onHistoryFieldClick
                                        )
                                    }
                                }

                                // 4. Trei citiri gravate: baterie, autoconsum invertor,
                                // temperatura invertor - acelasi set ca pe TABLOU-ul Retro.
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = CPanel,
                                    tonalElevation = 1.dp
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val batV = data?.let { String.format(Locale.US, "%.1f", it.batteryVoltage) } ?: "--"
                                        val invW = data?.inverterLoss?.roundToInt()?.toString() ?: "--"
                                        val tempC = data?.let { String.format(Locale.US, "%.1f", it.inverterTemp) } ?: "--"

                                        EngravedReadout(R.drawable.ic_simple_battery, "BATERIE", batV, "V", CHouse, Modifier.weight(1f))
                                        EngravedReadout(R.drawable.ic_simple_inverter, "INVERTOR", invW, "W", CHouse, Modifier.weight(1f))
                                        EngravedReadout(R.drawable.ic_simple_thermo, "TEMP", tempC, "°C", CBat, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        RetroTab.ENERGY -> energyContent()
                        RetroTab.SYSTEM -> systemContent()
                        RetroTab.SETTINGS -> settingsContent()
                    }
                }
            }
            SimpleBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
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
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            "Solar Monitor",
            color = CText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            "Growatt SPF 6000 ES Plus",
            color = CMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
private fun EnergyFlow(data: SolarData?, modifier: Modifier = Modifier, onHistoryClick: (HistoryMetric) -> Unit) {
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

    BoxWithConstraints(
        modifier.fillMaxWidth()
    ) {
        val imageSize = kotlin.math.min(58f, kotlin.math.max(44f, maxHeight.value * 0.26f)).dp
        
        Canvas(Modifier.fillMaxSize()) {
            val imageRadius = imageSize.toPx() / 2f
            val textHeight = 31.dp.toPx()

            // Asezare ca in referinta: panoul sus la mijloc, casa jos la mijloc, bateria in
            // stanga ei, stalpul in dreapta. Coordonatele trebuie sa cada peste centrele
            // ilustratiilor de mai jos, altfel liniile pornesc de nicaieri.
            val sideInset = 50.dp.toPx()
            val bottomRow = size.height - (imageRadius + textHeight)
            val solar = Offset(size.width / 2f, imageRadius)
            val house = Offset(size.width / 2f, bottomRow)
            val batteryNode = Offset(sideInset, bottomRow)
            val gridNode = Offset(size.width - sideInset, bottomRow)

            // Liniile se scurteaza la ambele capete ca sa nu intre peste ilustratii. Capetele
            // au decupaje diferite: pe verticala nodul ocupa si randul de valoare de sub poza,
            // pe orizontala doar latimea pozei.
            fun connection(
                rawStart: Offset,
                rawEnd: Offset,
                active: Boolean,
                color: Color,
                startGap: Float = 40.dp.toPx(),
                endGap: Float = 40.dp.toPx()
            ) {
                val dx = rawEnd.x - rawStart.x
                val dy = rawEnd.y - rawStart.y
                val len = kotlin.math.hypot(dx, dy)
                if (len <= startGap + endGap) return
                val ux = dx / len
                val uy = dy / len
                val start = Offset(rawStart.x + ux * startGap, rawStart.y + uy * startGap)
                val end = Offset(rawEnd.x - ux * endGap, rawEnd.y - uy * endGap)
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

            // Capatul de sus trece pe langa poza SI pe langa valoarea de sub ea.
            connection(
                solar, house, active = pv > DEAD, color = CPv,
                startGap = imageRadius + textHeight + 2.dp.toPx(), endGap = imageRadius + 5.dp.toPx()
            )
            connection(
                batteryNode, house,
                active = discharging || charging,
                color = if (charging) CPv else CBat,
                startGap = imageRadius + 7.dp.toPx(), endGap = imageRadius + 7.dp.toPx()
            )
            connection(
                gridNode, house, active = grid > DEAD, color = CGrid,
                startGap = imageRadius + 7.dp.toPx(), endGap = imageRadius + 7.dp.toPx()
            )
        }

        FlowIllustration(
            modifier = Modifier.align(Alignment.TopCenter),
            imageRes = R.drawable.simple_flow_panou,
            description = "Panouri",
            value = wholeNumber(data?.pv),
            color = CPv,
            imageSize = imageSize,
            onClick = { onHistoryClick(historyMetric("pv_power")) }
        )
        FlowIllustration(
            modifier = Modifier.align(Alignment.BottomStart),
            imageRes = R.drawable.simple_flow_baterie,
            description = "Baterie",
            value = signedNumber(data?.batteryDisplay),
            color = data?.let { batteryColor(it.batteryVoltage) } ?: CMuted,
            imageSize = imageSize,
            onClick = { onHistoryClick(historyMetric("battery_voltage")) }
        )
        FlowIllustration(
            modifier = Modifier.align(Alignment.BottomCenter),
            imageRes = R.drawable.simple_flow_casa,
            description = "Casa",
            value = wholeNumber(data?.house),
            color = CHouse,
            imageSize = imageSize,
            onClick = { onHistoryClick(historyMetric("output_power")) }
        )
        FlowIllustration(
            modifier = Modifier.align(Alignment.BottomEnd),
            imageRes = R.drawable.simple_flow_stalp,
            description = "Retea",
            value = wholeNumber(if (data == null) null else grid),
            color = CGrid,
            imageSize = imageSize
        )
    }
}

/**
 * Un nod din diagrama de flux: ilustratia 3D si valoarea de sub ea.
 * Ilustratiile sunt decorative si au `contentDescription = null`; descrierea utila
 * sta pe intreg nodul, impreuna cu valoarea, ca cititorul de ecran sa o citeasca o data.
 */
@Composable
private fun FlowIllustration(
    modifier: Modifier,
    imageRes: Int,
    description: String,
    value: String,
    color: Color,
    imageSize: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Column(
        modifier = modifier
            .width(100.dp)
            .then(clickModifier)
            .semantics { contentDescription = "$description $value wati" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            modifier = Modifier.size(imageSize)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = color,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                style = LocalTextStyle.current.copy(shadow = ValueShadow)
            )
            Spacer(Modifier.width(3.dp))
            Text("W", color = CMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
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
        defaultRange = "1d",
        ranges = listOf("2h", "1d", "7d")
    ),
    HistoryMetric(
        title = "Productie PV",
        field = "pv_power",
        unit = "W",
        color = CPv,
        defaultRange = "1d",
        ranges = listOf("2h", "1d", "7d")
    ),
    HistoryMetric(
        title = "Baterie",
        field = "battery_voltage",
        unit = "V",
        color = CBat,
        defaultRange = "1d",
        ranges = listOf("2h", "1d", "7d"),
        thresholds = listOf(
            ChartThreshold(48.0, CGrid),
            ChartThreshold(57.5, CGrid)
        )
    ),
    HistoryMetric(
        title = "Energie produsa",
        field = "energy_pv_today",
        unit = "kWh",
        color = CPv,
        defaultRange = "7d",
        ranges = listOf("2h", "1d", "7d"),
        chartStyle = ChartStyle.Bar
    ),
    HistoryMetric(
        title = "Energie consumata",
        field = "energy_load_today",
        unit = "kWh",
        color = CHouse,
        defaultRange = "7d",
        ranges = listOf("2h", "1d", "7d"),
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
private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

@Composable
private fun RetroSettingsPage(
    dashboardStyle: DashboardStyle,
    settings: AlarmSettings,
    ringtoneTitle: String,
    @Suppress("UNUSED_PARAMETER") version: String,
    onDashboardStyleChange: (DashboardStyle) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onTestAlarm: () -> Unit
) {
    RetroSettingsArtworkPage(
        dashboardStyle = dashboardStyle,
        settings = settings,
        ringtoneTitle = ringtoneTitle,
        onDashboardStyleChange = onDashboardStyleChange,
        onEnabledChange = onEnabledChange,
        onThresholdChange = onThresholdChange,
        onCooldownChange = onCooldownChange,
        onVibrateChange = onVibrateChange,
        onPickRingtone = onPickRingtone,
        onTestAlarm = onTestAlarm
    )
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
                // Implicit Material coloreaza pista inactiva cu `surfaceVariant`, ceea ce pe
                // paleta Simple iese lavanda si nu se potriveste cu nimic. O legam de paleta.
                SliderDefaults.colors(
                    thumbColor = CHouse,
                    activeTrackColor = CHouse,
                    inactiveTrackColor = CLine,
                    activeTickColor = CPanel,
                    inactiveTickColor = CMuted
                )
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
    selectedRange: String,
    onRangeChange: (String) -> Unit,
    title: String = metric.title,
    embedded: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chrome = dashboardChrome(retro)
    val accent = historyAccent(metric.field, metric.color, retro)
    val history = rememberEnergyHistory(metric.field, selectedRange, 0, "Nu pot incarca istoricul")
    val series = history.series
    val loading = history.loading
    val error = history.error

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
        val rangeSelector: @Composable () -> Unit = {
            RangeSelector(
                ranges = metric.ranges,
                selectedRange = selectedRange,
                color = accent,
                retro = retro,
                compact = compact
            ) { onRangeChange(it) }
        }
        if (compact) {
            // Ordinea paginii ENERGIE: intervalul (CAT) inaintea titlului si a graficului.
            rangeSelector()
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    title.uppercase(Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    color = accent,
                    fontFamily = chrome.font,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    series?.let { energySeriesSpan(it, metric).lowercase(Locale.getDefault()) } ?: "",
                    color = chrome.muted,
                    fontFamily = chrome.font,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        } else {
            Text(
                if (retro) title.uppercase(Locale.getDefault()) else title,
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
            Spacer(Modifier.height(14.dp))
            rangeSelector()
        }
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
                                HistoryStatsGrid(stats = stats, metric = metric, retro = retro, compact = true, range = loaded.range)
                            }
                        }
                    } else {
                        HistoryChart(series = loaded, metric = metric, retro = retro)
                        Spacer(Modifier.height(14.dp))
                        loaded.stats?.let { stats ->
                            HistoryStatsGrid(stats = stats, metric = metric, retro = retro, range = loaded.range)
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
    // Pe 2 ore barele au cateva zecimi de kWh: scala nu mai porneste fortat de la 1 kWh.
    val maxValue = max(values.maxOrNull() ?: 0.1, 0.1)
    val tickIndices = energyTickIndices(series.points.map { it.time }, series.range, LocalZone)

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatHistoryValue(maxValue, metric.unit), color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
            Text(barCountLabel(series), color = chrome.muted, fontFamily = chrome.font, fontSize = if (compact) 8.sp else 11.sp)
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
                val labelBand = 16f * density
                val width = size.width
                val height = size.height - labelBand
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
                    // Fiecare bara poarta ora/ziua de inceput; etichetam doar momentele rotunde.
                    val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = chrome.muted.toArgb()
                        textSize = 10f * density
                        textAlign = Paint.Align.CENTER
                    }
                    var lastRight = Float.NEGATIVE_INFINITY
                    tickIndices.forEach { index ->
                        val x = slot * index + slot / 2f
                        val label = energyTickLabel(series.points[index].time, series.range, LocalZone)
                        val half = xPaint.measureText(label) / 2f
                        if (x - half < lastRight + 4f * density || x - half < 0f || x + half > width) return@forEach
                        drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 2f * density, xPaint)
                        lastRight = x + half
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
    compact: Boolean = false,
    range: String = "7d"
) {
    val accent = historyAccent(metric.field, metric.color, retro)
    if (compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            energyStatPairs(stats, metric).forEach { (label, value) ->
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = label,
                    value = value,
                    color = accent,
                    retro = retro,
                    compact = true
                )
            }
        }
        return
    }
    if (metric.chartStyle == ChartStyle.Bar) {
        val slot = when (range) {
            "2h" -> "10 min"
            "1d" -> "ora"
            else -> "zi"
        }
        val avgLabel = "Medie/$slot"
        val maxLabel = "Max $slot"
        val lastLabel = if (range == "2h") "Ultimele 10 min" else "Ultima $slot"
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Modifier.weight(1f), "Total", formatHistoryValue(stats.sum, metric.unit), accent, retro)
                StatTile(Modifier.weight(1f), avgLabel, formatHistoryValue(stats.avg, metric.unit), accent, retro)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Modifier.weight(1f), maxLabel, formatHistoryValue(stats.max, metric.unit), accent, retro)
                StatTile(Modifier.weight(1f), lastLabel, formatHistoryValue(stats.last, metric.unit), accent, retro)
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

    val stepMinutes = energyTickStepMinutes(series.range)
    val formatter = if (stepMinutes < 1440L) TimeFormatter else DayFormatter
    var tick = floorToStep(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(first), LocalZone), stepMinutes)
    if (tick.toInstant().toEpochMilli() < first) {
        tick = tick.plusMinutes(stepMinutes)
    }

    val maxTicks = 16
    val out = mutableListOf<TimeTick>()
    while (tick.toInstant().toEpochMilli() <= last && out.size < maxTicks) {
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
    "battery_voltage" -> "Tensiune baterie cu praguri 48V / 57.5V"
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

@Composable
private fun SimpleBottomNavigation(
    selectedTab: RetroTab,
    onTabSelected: (RetroTab) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        color = CPanelRaised,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RetroTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val color = if (selected) CHouse else CMuted
                val icon = when (tab) {
                    RetroTab.DASHBOARD -> R.drawable.ic_simple_tab_dashboard
                    RetroTab.ENERGY -> R.drawable.ic_simple_tab_energy
                    RetroTab.SYSTEM -> R.drawable.ic_simple_tab_system
                    RetroTab.SETTINGS -> R.drawable.ic_simple_tab_settings
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(tab) }
                        .semantics {
                            contentDescription = "${tab.label}${if (selected) ", selectat" else ""}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = color
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (selected) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .width(28.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(CHouse)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleServerMetrics(data: SolarData?) {
    val uptime = data?.serverUptimeSeconds?.let {
        val totalHours = (it / 3600).toInt()
        val days = totalHours / 24
        val hours = totalHours % 24
        if (days > 0) "${days}z ${hours}h" else "${hours}h"
    } ?: "—"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CPanel,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("Server", color = CText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SimpleMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "CPU",
                    value = data?.serverCpuPercent?.let { "${it.roundToInt()}%" } ?: "—",
                    color = CPv
                )
                SimpleMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "MEM",
                    value = data?.serverMemoryPercent?.let { "${it.roundToInt()}%" } ?: "—",
                    color = CBat
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SimpleMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "UP",
                    value = data?.serverUploadKbps?.let { String.format(Locale.US, "%.1f KB/s", it) } ?: "—",
                    color = CHouse
                )
                SimpleMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "UPTIME",
                    value = uptime,
                    color = CMuted
                )
            }
        }
    }
}

@Composable
private fun SimpleMetricBox(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CPanelSoft)
            .padding(12.dp)
    ) {
        Text(label, color = CMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

package com.rolling7.solar

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val RETRO_DEAD = 50.0

internal enum class RetroBatteryFlow {
    CHARGING,
    DISCHARGING,
    IDLE
}

internal data class RetroEnergyFlowState(
    val battery: RetroBatteryFlow,
    val solarToHouse: Boolean,
    val solarToBattery: Boolean,
    val batteryToHouse: Boolean,
    val gridToHouse: Boolean,
    val gridToBattery: Boolean
)

internal fun retroBatteryFlowColor(flow: RetroBatteryFlow): Color = when (flow) {
    RetroBatteryFlow.CHARGING -> RetroSage
    RetroBatteryFlow.DISCHARGING -> RetroYellow
    RetroBatteryFlow.IDLE -> RetroOlive
}

/**
 * Stabileste traseele energetice inainte de desenare. Semnul valorii afisate a bateriei are prioritate:
 * pozitiv inseamna incarcare, negativ inseamna descarcare, inclusiv pentru puteri mici precum -44 W.
 * Campurile charge/support sunt fallback cand valoarea semnata este exact zero.
 */
internal fun resolveRetroEnergyFlow(
    pv: Double,
    house: Double,
    batteryDisplay: Double,
    batteryCharge: Double,
    batterySupport: Double,
    gridImport: Double,
    gridCharge: Double
): RetroEnergyFlowState {
    val battery = when {
        batteryDisplay > 0.0 -> RetroBatteryFlow.CHARGING
        batteryDisplay < 0.0 -> RetroBatteryFlow.DISCHARGING
        batteryCharge > RETRO_DEAD && batterySupport <= RETRO_DEAD -> RetroBatteryFlow.CHARGING
        batterySupport > RETRO_DEAD -> RetroBatteryFlow.DISCHARGING
        else -> RetroBatteryFlow.IDLE
    }
    val solarActive = pv > RETRO_DEAD
    val houseActive = house > RETRO_DEAD
    return RetroEnergyFlowState(
        battery = battery,
        solarToHouse = solarActive && houseActive,
        solarToBattery = solarActive && battery == RetroBatteryFlow.CHARGING,
        batteryToHouse = houseActive && battery == RetroBatteryFlow.DISCHARGING,
        gridToHouse = houseActive && gridImport > RETRO_DEAD,
        gridToBattery = gridCharge > RETRO_DEAD && battery == RetroBatteryFlow.CHARGING
    )
}

internal enum class RetroTab(val label: String) {
    DASHBOARD("TABLOU"),
    ENERGY("ENERGIE"),
    SYSTEM("SISTEM"),
    SETTINGS("SETARI")
}

internal fun retroTabAfterSwipe(
    current: RetroTab,
    horizontalDistance: Float,
    verticalDistance: Float,
    threshold: Float
): RetroTab {
    val horizontal = abs(horizontalDistance)
    val vertical = abs(verticalDistance)
    if (horizontal < threshold || horizontal <= vertical * 1.25f) return current

    val nextIndex = current.ordinal + if (horizontalDistance < 0f) 1 else -1
    return RetroTab.entries.getOrNull(nextIndex) ?: current
}

internal enum class RetroSystemSlot(val historyField: String?) {
    HOUSE("output_power"),
    SOLAR("pv_power"),
    BATTERY("battery_voltage"),
    INVERTER_CONSUMPTION(null),
    TEMPERATURE(null),
    GRID(null)
}

internal data class RetroSystemReadings(
    val house: String,
    val solar: String,
    val batteryVoltage: String,
    val inverterConsumption: String,
    val temperature: String,
    val gridVoltage: String
)

internal fun retroSystemReadings(data: SolarData?): RetroSystemReadings {
    if (data == null) {
        return RetroSystemReadings(
            house = "—",
            solar = "—",
            batteryVoltage = "—",
            inverterConsumption = "—",
            temperature = "—",
            gridVoltage = "—"
        )
    }
    return RetroSystemReadings(
        house = data.house.roundToInt().toString(),
        solar = data.pv.roundToInt().toString(),
        batteryVoltage = String.format(Locale.US, "%.2f", data.batteryVoltage),
        inverterConsumption = data.inverterLoss.roundToInt().toString(),
        temperature = String.format(Locale.US, "%.1f", data.inverterTemp),
        gridVoltage = String.format(Locale.US, "%.1f", data.gridVoltage)
    )
}

internal fun retroSystemTemperatureColor(value: Double?): Color = when {
    value == null -> RetroOlive
    value >= 55.0 -> RetroRed
    value >= 45.0 -> RetroYellow
    else -> RetroSage
}

internal fun retroSystemUptimeText(seconds: Double?): String {
    if (seconds == null) return "UP —"
    val totalHours = (seconds / 3_600.0).toInt().coerceAtLeast(0)
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (days > 0) "UP ${days}z ${hours}h" else "UP ${hours}h"
}

@Composable
internal fun RetroDashboard(
    data: SolarData?,
    bms: BmsState?,
    alarmThresholdW: Int,
    selectedTab: RetroTab,
    onTabSelected: (RetroTab) -> Unit,
    onEnergyFieldClick: (String) -> Unit,
    energyContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.retro_page_background_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
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
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enterOffset: (Int) -> Int = { width -> if (forward) width else -width }
                        val exitOffset: (Int) -> Int = { width -> if (forward) -width else width }
                        (
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 240),
                                initialOffsetX = enterOffset
                            ) + fadeIn(animationSpec = tween(durationMillis = 180))
                        ).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = 240),
                                targetOffsetX = exitOffset
                            ) + fadeOut(animationSpec = tween(durationMillis = 180))
                        )
                    },
                    label = "Navigare pagini Retro",
                    modifier = Modifier.fillMaxSize()
                ) { visibleTab ->
                    when (visibleTab) {
                        RetroTab.DASHBOARD -> RetroOverviewPage(
                            data = data,
                            bms = bms,
                            alarmThresholdW = alarmThresholdW,
                            onEnergyFieldClick = onEnergyFieldClick,
                            // Detaliul bateriei sta pe SISTEM.
                            onBmsClick = { onTabSelected(RetroTab.SYSTEM) }
                        )
                        RetroTab.ENERGY -> energyContent()
                        RetroTab.SYSTEM -> RetroSystemPage(
                            data = data,
                            bms = bms,
                            onEnergyFieldClick = onEnergyFieldClick
                        )
                        RetroTab.SETTINGS -> settingsContent()
                    }
                }
            }
            RetroBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }
    }
}

internal fun Modifier.retroSwipeNavigation(
    selectedTab: RetroTab,
    threshold: Float,
    onTabSelected: (RetroTab) -> Unit
): Modifier = pointerInput(selectedTab, threshold) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Final
        )
        var horizontalDistance = 0f
        var verticalDistance = 0f
        var claimedByControl = down.isConsumed
        var pressed = true

        while (pressed) {
            val event = awaitPointerEvent(pass = PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            horizontalDistance += change.position.x - change.previousPosition.x
            verticalDistance += change.position.y - change.previousPosition.y
            claimedByControl = claimedByControl || change.isConsumed
            pressed = change.pressed
        }

        if (!claimedByControl) {
            val destination = retroTabAfterSwipe(
                current = selectedTab,
                horizontalDistance = horizontalDistance,
                verticalDistance = verticalDistance,
                threshold = threshold
            )
            if (destination != selectedTab) onTabSelected(destination)
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
    bms: BmsState?,
    alarmThresholdW: Int,
    onEnergyFieldClick: (String) -> Unit,
    onBmsClick: () -> Unit
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = 7.dp, top = 18.dp, end = 7.dp, bottom = 5.dp)
    ) {
        val density = LocalDensity.current
        val cardWidth = maxWidth * 0.95f
        val originalLiveHeight = maxWidth / (1_386f / 1_011f)
        val flowTopPx = with(density) {
            originalLiveHeight.roundToPx() + (-6).dp.roundToPx() + 30
        }

        RetroLivePanel(
            data = data,
            alarmThresholdW = alarmThresholdW,
            onHouseHistoryClick = { onEnergyFieldClick("output_power") },
            onPvHistoryClick = { onEnergyFieldClick("pv_power") },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(cardWidth)
                .aspectRatio(1_386f / 1_011f)
                .offset { IntOffset(x = 0, y = 40) }
        )
        RetroFlowPanel(
            data = data,
            onEnergyFieldClick = onEnergyFieldClick,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(cardWidth)
                .aspectRatio(1_405f / 939f)
                .offset { IntOffset(x = 0, y = flowTopPx) }
        )
        RetroOverviewTelemetry(
            data = data,
            bms = bms,
            onBatteryClick = { onEnergyFieldClick("battery_voltage") },
            onBmsClick = onBmsClick,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun RetroOverviewTelemetry(
    data: SolarData?,
    bms: BmsState?,
    onBatteryClick: () -> Unit,
    onBmsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val batteryVoltage = data?.let { String.format(Locale.US, "%.1f", it.batteryVoltage) } ?: "—"
    val inverterLoss = data?.let { it.inverterLoss.roundToInt().toString() } ?: "—"
    val inverterTemperature = data?.let { String.format(Locale.US, "%.1f", it.inverterTemp) } ?: "—"
    // Temperatura invertorului nu mai are placa proprie: apare discret sub eticheta "Inv".
    val inverterTemperatureShort = data?.let { "${it.inverterTemp.roundToInt()}°C" }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 34.dp)
    ) {
        val primaryGap = 20.dp
        val primaryDialWidth = (maxWidth - primaryGap) / 2f
        val primaryDialHeight = primaryDialWidth / (600f / 190f)
        // Placile BMS pastreaza inaltimea fostei placi a temperaturii (Bat/Inv cu 20% mai inalte).
        val bmsPlateHeight = primaryDialHeight / 1.2f

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(primaryGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RetroCompactTelemetryDial(
                    shortLabel = "Bat",
                    dialRes = R.drawable.retro_dashboard_dial_battery,
                    value = batteryVoltage,
                    description = "Baterie $batteryVoltage V",
                    onClick = onBatteryClick,
                    modifier = Modifier
                        .width(primaryDialWidth)
                        .height(primaryDialHeight)
                )
                RetroCompactTelemetryDial(
                    shortLabel = "Inv",
                    subLabel = inverterTemperatureShort,
                    dialRes = R.drawable.retro_dashboard_dial_inverter,
                    value = inverterLoss,
                    description = "Consum propriu invertor $inverterLoss W, temperatura $inverterTemperature grade Celsius",
                    modifier = Modifier
                        .width(primaryDialWidth)
                        .height(primaryDialHeight)
                )
            }
            RetroBmsStrip(
                state = bms,
                plateHeight = bmsPlateHeight,
                onClick = onBmsClick
            )
        }
    }
}

@Composable
private fun RetroCompactTelemetryDial(
    shortLabel: String,
    dialRes: Int,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    onClick: (() -> Unit)? = null
) {
    BoxWithConstraints(
        modifier = modifier
            .semantics { contentDescription = description },
    ) {
        Image(
            painter = painterResource(dialRes),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = shortLabel,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * 0.085f, y = (-1).dp)
                .offset { IntOffset(x = 20, y = -20) },
            color = RetroSage,
            fontFamily = RetroMono,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.35.sp,
            maxLines = 1
        )
        if (subLabel != null) {
            Text(
                text = subLabel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * 0.085f, y = maxHeight * 0.20f)
                    .offset { IntOffset(x = 20, y = -20) },
                color = RetroSage,
                fontFamily = RetroMono,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.sp,
                maxLines = 1
            )
        }
        RetroVfdDisplay(
            value = value,
            unit = "",
            color = RetroSage,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * 0.27f)
                .width(maxWidth * 0.51f)
                .height(maxHeight * 0.64f),
            embedded = true,
            description = description
        )
    }
}

@Composable
internal fun RetroEnergyArtworkPage(
    data: SolarData?,
    bill: GridMonth?,
    selectedField: String,
    selectedRange: String,
    chartTitle: String,
    spanLabel: String,
    stats: List<Pair<String, String>>,
    onFieldClick: (String) -> Unit,
    onRangeClick: (String) -> Unit,
    chartContent: @Composable (Modifier) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 7.dp, top = 4.dp, end = 7.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RetroEnergyTopArtwork(
            data = data,
            bill = bill,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .aspectRatio(1_400f / 298f)
        )
        RetroEnergyTodayArtwork(
            data = data,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .aspectRatio(1_400f / 607f)
        )
        RetroEnergyControlsArtwork(
            selectedField = selectedField,
            selectedRange = selectedRange,
            chartTitle = chartTitle,
            spanLabel = spanLabel,
            stats = stats,
            onFieldClick = onFieldClick,
            onRangeClick = onRangeClick,
            chartContent = chartContent,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .weight(1f)
        )
    }
}

/**
 * Culoarea LED-ului, reevaluata la o secunda (si fara date noi trebuie sa treaca in portocaliu);
 * recompune doar cand se schimba culoarea. API-ul trimite timestamp null dupa 15 s fara date, asa
 * ca LED-ul tine minte ultimul moment vazut, altfel ar sari direct din verde in rosu.
 */
@Composable
private fun rememberLiveLed(timestamp: String?): State<LiveLed> {
    val latest by rememberUpdatedState(timestamp)
    return produceState(liveLedFor(timestamp, System.currentTimeMillis())) {
        var lastSeen = latest
        while (true) {
            lastSeen = latest ?: lastSeen
            value = liveLedFor(lastSeen, System.currentTimeMillis())
            delay(1_000)
        }
    }
}

@Composable
private fun RetroEnergyTopArtwork(
    data: SolarData?,
    bill: GridMonth?,
    modifier: Modifier = Modifier
) {
    val led by rememberLiveLed(data?.timestamp)
    val ledDescription = when (led) {
        LiveLed.FRESH -> "date live la zi"
        LiveLed.LATE -> "fara date noi de peste 10 secunde"
        LiveLed.STALE -> "fara date noi de peste un minut"
    }
    // Fanta vechilor taburi PRODUCTIE / CONSUM / ISTORIC a fost acoperita in asset cu tabla placii
    // (v3.29); pe placa raman titlul, LED-ul si, din v3.33, afisajul cu factura lunii intre ele.
    BoxWithConstraints(modifier.semantics { contentDescription = "Energie, $ledDescription" }) {
        Image(
            painter = painterResource(R.drawable.retro_energy_top_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        // Spatiul liber al placii e intre titlu (se termina la x ~ 345) si LED (incepe la ~ 858), pe un asset de 1024 x 218.
        RetroBillReadout(bill = bill, plateWidth = maxWidth, plateHeight = maxHeight)
        // LED-ul pictat e verde. Portocaliu si rosu sunt aceeasi fotografie recolorata, decupata
        // din asset la 840,53 / 120 x 120 px (asset 1024 x 218) si pusa exact peste.
        if (led != LiveLed.FRESH) {
            Image(
                painter = painterResource(
                    if (led == LiveLed.LATE) R.drawable.retro_energy_led_orange else R.drawable.retro_energy_led_red
                ),
                contentDescription = null,
                modifier = Modifier
                    .offset(x = maxWidth * (840f / 1_024f), y = maxHeight * (53f / 218f))
                    .size(width = maxWidth * (120f / 1_024f), height = maxHeight * (120f / 218f)),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

/**
 * Factura lunii in curs: kWh luati din retea de la 1 a lunii, pe un afisaj VFD galben stins cu sticla mica,
 * semi-transparenta, in placa de sus a ENERGIE. Luna e scrisa mare, "FACTURA" mic sub ea, apoi cifrele cu
 * segmente ale [RetroVfdDisplay] (ele conteaza, deci sunt cele mai mari) si "kWh" mic. Textele sunt in dp,
 * nu in sp: placa are marime fixa si nu trebuie sa creasca odata cu fontul sistemului. Pozitia e in unitati
 * de asset 1024 x 218; fereastra se termina la x = 813, inainte de LED (858), si incepe dupa titlu (345).
 */
@Composable
private fun RetroBillReadout(bill: GridMonth?, plateWidth: Dp, plateHeight: Dp) {
    val value = billKwhText(bill?.kwh)
    val month = bill?.monthShort?.uppercase(Locale.ROOT) ?: "—"
    val shape = RoundedCornerShape(8.dp)
    val density = LocalDensity.current
    fun fixedSp(dp: Float) = with(density) { dp.dp.toSp() }
    fun glowOf(c: Color) = Shadow(color = c.copy(alpha = 0.7f), offset = Offset.Zero, blurRadius = 8f)
    val digits = RetroYellow.copy(alpha = 0.80f)
    val monthColor = RetroYellow.copy(alpha = 0.85f)
    Row(
        Modifier
            .offset(x = plateWidth * (453f / 1_024f), y = plateHeight * (59f / 218f))
            .width(plateWidth * (360f / 1_024f))
            .height(plateHeight * (100f / 218f))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050805), Color(0xFF10170C), Color(0xFF030503)).map { it.copy(alpha = 0.55f) }
                )
            )
            .border(1.5.dp, RetroBrassDark.copy(alpha = 0.9f), shape)
            .clearAndSetSemantics {
                contentDescription = "Factura${bill?.let { " " + it.monthShort } ?: ""}: $value kWh luati din retea de la inceputul lunii"
            }
            .padding(start = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.padding(end = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = month,
                color = monthColor,
                fontFamily = RetroMono,
                fontSize = fixedSp(16f),
                lineHeight = fixedSp(16f * 1.1f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                maxLines = 1,
                style = LocalTextStyle.current.copy(shadow = glowOf(monthColor))
            )
            Text(
                text = "FACTURA",
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = fixedSp(8f),
                lineHeight = fixedSp(8f * 1.2f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1
            )
        }
        RetroVfdDisplay(
            value = value,
            unit = "",
            color = digits,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            embedded = true,
            horizontalPadding = 3.dp
        )
        // "kWh" il desenez eu, nu afisajul: cifrele iau tot locul ramas, iar unitatea ramane mai mica decat ele.
        Text(
            text = "kWh",
            color = RetroMuted,
            fontFamily = RetroMono,
            fontSize = fixedSp(11f),
            lineHeight = fixedSp(11f * 1.2f),
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            modifier = Modifier.padding(end = 8.dp),
            style = LocalTextStyle.current.copy(shadow = glowOf(RetroMuted))
        )
    }
}

/** Afisaj ingropat in sasiu: placa inchisa, opaca (acopera textul pictat de dedesubt), text mono. */
@Composable
private fun RetroInsetReadout(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = CornerRadius(4.dp.toPx())
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0E110B), Color(0xFF151810), Color(0xFF10130C))
                ),
                cornerRadius = radius
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.55f),
                cornerRadius = radius,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Text(
            text = text,
            color = color,
            fontFamily = RetroMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            // Rand strans si centrat: cu fontul de sistem marit textul cobora pe marginea placii.
            lineHeight = 10.sp,
            style = LocalTextStyle.current.copy(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RetroEnergyControlsArtwork(
    selectedField: String,
    selectedRange: String,
    chartTitle: String,
    spanLabel: String,
    stats: List<Pair<String, String>>,
    onFieldClick: (String) -> Unit,
    onRangeClick: (String) -> Unit,
    chartContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val scaleX = maxWidth / 1_045f
        val scaleY = maxHeight / 1_292f
        val subject = energySubjectForField(selectedField)
        val mode = energyModeForField(selectedField)
        val selectedAccent = retroEnergySubjectAccent(subject)

        Image(
            painter = painterResource(R.drawable.retro_energy_controls_chart_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        // 1. CE urmaresti: patru placi goale pictate (petic v3.32, scripts/patch-retro-energy-subjects.py);
        // pictograma si numele le deseneaza Compose. Coordonate in unitati de 1045 x 1292.
        val subjects = EnergySubject.values()
        val subjectWidth = 219.4f
        val subjectGap = 18.4f
        subjects.forEachIndexed { index, option ->
            val optionAccent = retroEnergySubjectAccent(option)
            RetroEnergyTouchTarget(
                description = "Grafic ${option.label.lowercase()}",
                selected = option == subject,
                accent = optionAccent,
                modifier = Modifier
                    .offset(x = scaleX * (56f + (subjectWidth + subjectGap) * index), y = scaleY * 62f)
                    .width(scaleX * subjectWidth)
                    .height(scaleY * 108f),
                onClick = { onFieldClick(energyFieldForSubject(option, selectedField)) }
            ) {
                RetroSubjectLabel(
                    subject = option,
                    color = if (option == subject) optionAccent else RetroMuted
                )
            }
        }

        // 2. CUM: placi native peste "PRODUCTIE ZILNICA / CONSUM ZILNIC" pictate. Ocupa doar
        // interiorul ramei (82..967 x 263..345), ca rama de bronz sa ramana la vedere.
        val modes = energyModesFor(subject)
        val modeWidth = 885f / modes.size
        modes.forEachIndexed { index, option ->
            RetroEnergyTouchTarget(
                description = "Mod ${option.label.lowercase()}",
                selected = option == mode,
                accent = selectedAccent,
                labelText = "${option.label} · ${option.unit}",
                modifier = Modifier
                    .offset(x = scaleX * (82f + modeWidth * index), y = scaleY * 263f)
                    .width(scaleX * modeWidth)
                    .height(scaleY * 82f),
                onClick = { onFieldClick(energyFieldFor(subject, option)) }
            )
        }

        // 3. CAT timp: rama pictata are doua butoane ("7d"/"30d" din fotografia veche); placile
        // native le acopera si impart aceeasi latime (365..685) la cate intervale exista.
        val ranges = retroEnergyRangesForField(selectedField)
        val rangeWidth = 320f / ranges.size
        ranges.forEachIndexed { index, range ->
            RetroEnergyTouchTarget(
                description = retroEnergyRangeDescription(range),
                selected = selectedRange == range,
                accent = selectedAccent,
                labelText = range,
                modifier = Modifier
                    .offset(x = scaleX * (365f + rangeWidth * index), y = scaleY * 390f)
                    .width(scaleX * rangeWidth)
                    .height(scaleY * 82f),
                onClick = { onRangeClick(range) }
            )
        }

        Box(
            modifier = Modifier
                .offset(x = scaleX * 270f, y = scaleY * 522f)
                .width(scaleX * 505f)
                .height(scaleY * 72f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF16180F), Color(0xFF0C100A), Color(0xFF15170E))
                    ),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }
            Text(
                text = chartTitle,
                color = selectedAccent,
                fontFamily = RetroMono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.35.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        chartContent(
            Modifier
                .offset(x = scaleX * 79f, y = scaleY * 603f)
                .width(scaleX * 887f)
                .height(scaleY * 410f)
        )

        // Cifrele intervalului, in partea de jos a panoului graficului.
        RetroEnergyStatsRow(
            stats = stats,
            accent = selectedAccent,
            modifier = Modifier
                .offset(x = scaleX * 100f, y = scaleY * 1_018f)
                .width(scaleX * 845f)
                .height(scaleY * 112f)
        )

        // Peste "Ultima actualizare: ACUM" pictat (scria ACUM si cu date vechi): de cand pornesc
        // datele si ce reprezinta un punct. Prospetimea datelor o arata LED-ul de sus.
        RetroInsetReadout(
            text = spanLabel,
            color = RetroMuted,
            modifier = Modifier
                .offset(x = scaleX * 66f, y = scaleY * 1_178f)
                .width(scaleX * 913f)
                .height(scaleY * 60f)
        )
    }
}

@Composable
private fun RetroEnergyStatsRow(
    stats: List<Pair<String, String>>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        stats.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.62f)
                        .background(RetroOlive.copy(alpha = 0.38f))
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    color = RetroMuted,
                    fontFamily = RetroMono,
                    fontSize = 7.sp,
                    lineHeight = 9.sp,
                    letterSpacing = 0.8.sp,
                    maxLines = 1
                )
                Text(
                    text = value,
                    color = accent,
                    fontFamily = RetroMono,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun RetroEnergyTouchTarget(
    description: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier,
    labelText: String? = null,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier
            .semantics {
                contentDescription = "$description${if (selected) ", selectat" else ""}"
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (labelText != null) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))) {
                drawRoundRect(
                    // Opac: dedesubt sunt etichete pictate vechi ("7d"/"30d", "PRODUCTIE ZILNICA").
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF22251A), Color(0xFF14170E), Color(0xFF1B1E14))
                    ),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
                if (selected) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.15f),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = 0.55f),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawLine(
                        color = accent,
                        start = Offset(size.width * 0.22f, size.height * 0.88f),
                        end = Offset(size.width * 0.78f, size.height * 0.88f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                } else {
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.30f),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(width = 0.75.dp.toPx())
                    )
                }
            }
            Text(
                text = labelText,
                color = if (selected) accent else RetroMuted,
                fontFamily = RetroMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        } else if (selected) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp))) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.075f),
                    cornerRadius = CornerRadius(7.dp.toPx())
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.42f),
                    cornerRadius = CornerRadius(7.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
                drawLine(
                    color = accent.copy(alpha = 0.20f),
                    start = Offset(size.width * 0.18f, size.height * 0.88f),
                    end = Offset(size.width * 0.82f, size.height * 0.88f),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = accent,
                    start = Offset(size.width * 0.22f, size.height * 0.88f),
                    end = Offset(size.width * 0.78f, size.height * 0.88f),
                    strokeWidth = 1.25.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        content?.invoke()
    }
}

/** Pictograma si numele subiectului, pe placa goala: pictograma deasupra, numele dedesubt. */
@Composable
private fun RetroSubjectLabel(subject: EnergySubject, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
            drawSubjectIcon(subject, color)
        }
        Text(
            text = subject.label,
            color = color,
            fontFamily = RetroMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            lineHeight = 10.sp,
            style = LocalTextStyle.current.copy(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            ),
            maxLines = 1
        )
    }
}

/** Pictograme simple, in linii: casa, panou (grila), baterie, stalp de retea. */
private fun DrawScope.drawSubjectIcon(subject: EnergySubject, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (subject) {
        EnergySubject.HOUSE -> {
            val roof = Path().apply {
                moveTo(w * 0.08f, h * 0.48f)
                lineTo(w * 0.5f, h * 0.06f)
                lineTo(w * 0.92f, h * 0.48f)
            }
            drawPath(roof, color, style = stroke)
            val body = Path().apply {
                moveTo(w * 0.2f, h * 0.42f)
                lineTo(w * 0.2f, h * 0.94f)
                lineTo(w * 0.8f, h * 0.94f)
                lineTo(w * 0.8f, h * 0.42f)
            }
            drawPath(body, color, style = stroke)
            drawLine(color, Offset(w * 0.5f, h * 0.94f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
        }
        EnergySubject.PANELS -> {
            drawRoundRect(color, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), CornerRadius(1.5.dp.toPx()), stroke)
            drawLine(color, Offset(w * 0.37f, h * 0.1f), Offset(w * 0.37f, h * 0.9f), stroke.width)
            drawLine(color, Offset(w * 0.63f, h * 0.1f), Offset(w * 0.63f, h * 0.9f), stroke.width)
            drawLine(color, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.9f, h * 0.5f), stroke.width)
        }
        EnergySubject.BATTERY -> {
            drawRoundRect(color, Offset(w * 0.3f, h * 0.2f), Size(w * 0.4f, h * 0.76f), CornerRadius(1.5.dp.toPx()), stroke)
            drawLine(color, Offset(w * 0.42f, h * 0.08f), Offset(w * 0.58f, h * 0.08f), stroke.width * 1.4f, StrokeCap.Round)
            drawLine(color, Offset(w * 0.4f, h * 0.55f), Offset(w * 0.6f, h * 0.55f), stroke.width * 0.8f, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.43f), Offset(w * 0.5f, h * 0.67f), stroke.width * 0.8f, StrokeCap.Round)
        }
        EnergySubject.GRID -> {
            // Stalp de inalta tensiune: picioare, doua brate, zabrele.
            drawLine(color, Offset(w * 0.5f, h * 0.04f), Offset(w * 0.28f, h * 0.96f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.04f), Offset(w * 0.72f, h * 0.96f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.18f, h * 0.3f), Offset(w * 0.82f, h * 0.3f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.3f, h * 0.58f), Offset(w * 0.7f, h * 0.58f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.36f, h * 0.3f), Offset(w * 0.64f, h * 0.58f), stroke.width * 0.8f, StrokeCap.Round)
            drawLine(color, Offset(w * 0.64f, h * 0.3f), Offset(w * 0.36f, h * 0.58f), stroke.width * 0.8f, StrokeCap.Round)
        }
    }
}

private fun retroEnergySubjectAccent(subject: EnergySubject): Color = when (subject) {
    EnergySubject.HOUSE -> RetroHouseBlue
    EnergySubject.PANELS -> RetroSage
    EnergySubject.BATTERY -> RetroYellow
    EnergySubject.GRID -> RetroRed
}

@Composable
private fun RetroEnergyTodayArtwork(
    data: SolarData?,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val scale = maxWidth / 1_400f
        Image(
            painter = painterResource(R.drawable.retro_energy_today_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        RetroVfdDisplay(
            value = retroDecimal(data?.energyPvToday),
            unit = "kWh",
            color = RetroSage,
            modifier = Modifier
                .offset(x = scale * 112f, y = scale * 278f)
                .width(scale * 528f)
                .height(scale * 201f),
            embedded = true,
            description = "Energie produsa astazi ${retroDecimal(data?.energyPvToday)} kWh"
        )
        RetroVfdDisplay(
            value = retroDecimal(data?.energyLoadToday),
            unit = "kWh",
            color = RetroHouseBlue,
            modifier = Modifier
                .offset(x = scale * 766f, y = scale * 278f)
                .width(scale * 528f)
                .height(scale * 201f),
            embedded = true,
            description = "Energie consumata astazi ${retroDecimal(data?.energyLoadToday)} kWh"
        )
    }
}

@Composable
private fun RetroSystemPage(data: SolarData?, bms: BmsState?, onEnergyFieldClick: (String) -> Unit) {
    var batteryHistory by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var temperatureHistory by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val histories = withContext(Dispatchers.IO) {
                SolarRepository.fetchHistory("battery_voltage", "1h") to
                    SolarRepository.fetchHistory("inverter_temp", "1h")
            }
            histories.first?.points?.let { batteryHistory = it }
            histories.second?.points?.let { temperatureHistory = it }
            delay(60_000)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 7.dp, top = 18.dp, end = 7.dp, bottom = 5.dp)
    ) {
        val density = LocalDensity.current
        val cardGap = with(density) { 40.toDp() }
        val infoLift = with(density) { 10.toDp() }
        val monitorGap = with(density) { 20.toDp() }
        val topCardHeight = maxWidth * 0.95f / (1_024f / 301f)
        val infoCardHeight = maxWidth * 0.95f / (971f / 942f)
        val infoCardBottom = cardGap + topCardHeight + cardGap - infoLift + infoCardHeight
        val monitorWidth = maxWidth * 0.99f
        val naturalMonitorHeight = monitorWidth / (1_030f / 531f)
        val monitorBottomOffset = 5.dp
        val availableMonitorHeight =
            (maxHeight + monitorBottomOffset - infoCardBottom - monitorGap).coerceAtLeast(1.dp)
        val monitorHeight = if (availableMonitorHeight < naturalMonitorHeight) {
            availableMonitorHeight
        } else {
            naturalMonitorHeight
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Aceeasi pornire verticala ca primul card din TABLOU.
                .offset { IntOffset(x = 0, y = 40) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(cardGap)
        ) {
            RetroSystemTopArtwork(
                data = data,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .aspectRatio(1_024f / 301f)
            )
            RetroSystemInfoArtwork(
                data = data,
                onEnergyFieldClick = onEnergyFieldClick,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .aspectRatio(971f / 942f)
                    .offset { IntOffset(x = 0, y = -10) }
            )
        }
        RetroSystemMonitorArtwork(
            data = data,
            bms = bms,
            batteryHistory = batteryHistory,
            temperatureHistory = temperatureHistory,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.99f)
                .height(monitorHeight)
                // Compenseaza padding-ul paginii: rama se lipeste vizual de NAV.
                .offset(y = monitorBottomOffset)
        )
    }
}

@Composable
private fun RetroSystemTopArtwork(
    data: SolarData?,
    modifier: Modifier = Modifier
) {
    val connected = data != null
    val statusDescription = if (connected) {
        "Sistem, telemetrie activa, invertor conectat, cod ${data.status.roundToInt()}, ultima actualizare ${retroTimestamp(data.timestamp)}"
    } else {
        "Sistem fara date de telemetrie"
    }
    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription = statusDescription
        }
    ) {
        val scaleX = maxWidth / 1_400f
        val scaleY = maxHeight / 411f
        Image(
            painter = painterResource(R.drawable.retro_system_top_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Text(
            text = if (connected) retroTimestamp(data.timestamp) else "FARA DATE",
            color = if (connected) RetroSage else RetroRed,
            fontFamily = RetroMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .offset(x = scaleX * 930f, y = scaleY * 294f)
                .width(scaleX * 300f)
        )
    }
}

@Composable
private fun RetroSystemInfoArtwork(
    data: SolarData?,
    onEnergyFieldClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val values = retroSystemReadings(data)
    BoxWithConstraints(modifier) {
        val scaleX = maxWidth / 971f
        val scaleY = maxHeight / 942f
        val leftX = scaleX * 66f
        val rightX = scaleX * 526f
        val displayWidth = scaleX * 362f
        val displayHeight = scaleY * 114f

        Image(
            painter = painterResource(R.drawable.retro_system_info_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        RetroSystemLiveDisplay(
            value = values.house,
            unit = "W",
            color = RetroHouseBlue,
            description = "Consum casa ${values.house} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick(RetroSystemSlot.HOUSE.historyField!!) },
            modifier = Modifier
                .offset(x = leftX, y = scaleY * 188f)
                .width(displayWidth)
                .height(displayHeight)
        )
        RetroSystemLiveDisplay(
            value = values.solar,
            unit = "W",
            color = RetroSage,
            description = "Panouri ${values.solar} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick(RetroSystemSlot.SOLAR.historyField!!) },
            modifier = Modifier
                .offset(x = rightX, y = scaleY * 188f)
                .width(displayWidth)
                .height(displayHeight)
        )
        RetroSystemLiveDisplay(
            value = values.batteryVoltage,
            unit = "V",
            color = RetroYellow,
            description = "Baterie ${values.batteryVoltage} V. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick(RetroSystemSlot.BATTERY.historyField!!) },
            modifier = Modifier
                .offset(x = leftX, y = scaleY * 451f)
                .width(displayWidth)
                .height(displayHeight)
        )
        RetroSystemLiveDisplay(
            value = values.inverterConsumption,
            unit = "W",
            color = RetroSage,
            description = "Consum propriu invertor ${values.inverterConsumption} W",
            modifier = Modifier
                .offset(x = rightX, y = scaleY * 451f)
                .width(displayWidth)
                .height(displayHeight)
        )
        RetroSystemLiveDisplay(
            value = values.temperature,
            unit = "°C",
            color = retroSystemTemperatureColor(data?.inverterTemp),
            description = "Temperatura invertor ${values.temperature} grade Celsius",
            modifier = Modifier
                .offset(x = leftX, y = scaleY * 706f)
                .width(displayWidth)
                .height(displayHeight)
        )
        RetroSystemLiveDisplay(
            value = values.gridVoltage,
            unit = "V",
            color = RetroRed,
            description = "Tensiune retea ${values.gridVoltage} V",
            modifier = Modifier
                .offset(x = rightX, y = scaleY * 706f)
                .width(displayWidth)
                .height(displayHeight)
        )
    }
}

@Composable
private fun RetroSystemLiveDisplay(
    value: String,
    unit: String,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val actionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(actionModifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color(0xFF030503),
                        Color(0xFF0C1209),
                        Color(0xFF020302)
                    )
                ),
                cornerRadius = CornerRadius(10.dp.toPx())
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
        }
        RetroVfdDisplay(
            value = value,
            unit = unit,
            color = color,
            modifier = Modifier.fillMaxSize(),
            embedded = true,
            // Descrierea apartine containerului; evitam anuntarea dubla in TalkBack.
            description = ""
        )
    }
}

@Composable
private fun RetroSystemMonitorArtwork(
    data: SolarData?,
    bms: BmsState?,
    batteryHistory: List<HistoryPoint>,
    temperatureHistory: List<HistoryPoint>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription =
                "Monitor sistem, CPU ${retroSystemPercentText(data?.serverCpuPercent)}, " +
                    "memorie ${retroSystemPercentText(data?.serverMemoryPercent)}, " +
                    retroSystemUptimeText(data?.serverUptimeSeconds)
        }
    ) {
        val scaleX = maxWidth / 1_030f
        val scaleY = maxHeight / 531f
        Image(
            painter = painterResource(R.drawable.retro_system_monitor_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Row(
            modifier = Modifier
                .offset(x = scaleX * 74f, y = scaleY * 24f)
                .width(scaleX * 882f)
                .height(scaleY * 58f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RetroServerMetric(
                label = "CPU",
                value = retroSystemPercentText(data?.serverCpuPercent),
                fraction = data?.serverCpuPercent?.div(100.0)?.toFloat(),
                color = RetroSage,
                modifier = Modifier.weight(1f)
            )
            RetroServerMetric(
                label = "MEM",
                value = retroSystemPercentText(data?.serverMemoryPercent),
                fraction = data?.serverMemoryPercent?.div(100.0)?.toFloat(),
                color = RetroYellow,
                modifier = Modifier.weight(1f)
            )
            Text(
                retroSystemUptimeText(data?.serverUptimeSeconds),
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(0.82f)
            )
            Text(
                if (data == null) "API OFFLINE" else "API ONLINE",
                color = if (data == null) RetroRed else RetroSage,
                fontFamily = RetroMono,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.88f)
            )
        }

        Row(
            modifier = Modifier
                .offset(x = scaleX * 74f, y = scaleY * 94f)
                .width(scaleX * 882f)
                .height(scaleY * 100f),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            RetroMiniTrend(
                label = "BATERIE · 60 MIN",
                current = data?.batteryVoltage,
                currentText = data?.let { String.format(Locale.US, "%.2f V", it.batteryVoltage) } ?: "— V",
                points = batteryHistory,
                minValue = 48.0,
                maxValue = 58.0,
                thresholds = listOf(48.0, 57.5),
                color = RetroYellow,
                modifier = Modifier.weight(1f)
            )
            RetroMiniTrend(
                label = "TEMP · 60 MIN",
                current = data?.inverterTemp,
                currentText = data?.let { String.format(Locale.US, "%.1f °C", it.inverterTemp) } ?: "— °C",
                points = temperatureHistory,
                minValue = 20.0,
                maxValue = 70.0,
                thresholds = listOf(45.0, 55.0),
                color = retroSystemTemperatureColor(data?.inverterTemp),
                modifier = Modifier.weight(1f)
            )
        }

        RetroBmsMonitor(
            state = bms,
            modifier = Modifier
                .offset(x = scaleX * 74f, y = scaleY * 205f)
                .width(scaleX * 882f)
                .height(scaleY * 260f)
        )
    }
}

@Composable
private fun RetroServerMetric(
    label: String,
    value: String,
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            "$label  $value",
            color = color,
            fontFamily = RetroMono,
            fontSize = 6.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRoundRect(
                color = RetroOlive.copy(alpha = 0.28f),
                cornerRadius = CornerRadius(size.height / 2f)
            )
            drawRoundRect(
                color = color.copy(alpha = 0.86f),
                size = Size(size.width * (fraction ?: 0f).coerceIn(0f, 1f), size.height),
                cornerRadius = CornerRadius(size.height / 2f)
            )
        }
    }
}

@Composable
private fun RetroMiniTrend(
    label: String,
    current: Double?,
    currentText: String,
    points: List<HistoryPoint>,
    minValue: Double,
    maxValue: Double,
    thresholds: List<Double>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .semantics {
            contentDescription = "$label, valoare curenta $currentText"
        }
            .fillMaxSize()
    ) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.18f),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
            repeat(3) { index ->
                val y = size.height * (index + 1) / 4f
                drawLine(
                    RetroOlive.copy(alpha = 0.16f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    0.6.dp.toPx()
                )
            }
            thresholds.forEach { threshold ->
                val normalized = ((threshold - minValue) / (maxValue - minValue)).toFloat().coerceIn(0f, 1f)
                val y = size.height * (1f - normalized)
                drawLine(
                    RetroRed.copy(alpha = 0.20f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    0.7.dp.toPx()
                )
            }

            val values = points.takeLast(120).map { it.value }.ifEmpty {
                current?.let(::listOf) ?: emptyList()
            }
            fun point(index: Int, value: Double): Offset {
                val x = if (values.size <= 1) size.width / 2f else size.width * index / (values.size - 1f)
                val normalized = ((value - minValue) / (maxValue - minValue)).toFloat().coerceIn(0f, 1f)
                return Offset(x, size.height * (1f - normalized))
            }
            values.zipWithNext().forEachIndexed { index, pair ->
                drawLine(
                    color = color.copy(alpha = 0.84f),
                    start = point(index, pair.first),
                    end = point(index + 1, pair.second),
                    strokeWidth = 1.35.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            values.lastOrNull()?.let { last ->
                val lastPoint = point(values.lastIndex, last)
                drawCircle(color.copy(alpha = 0.20f), 4.dp.toPx(), lastPoint)
                drawCircle(color, 1.8.dp.toPx(), lastPoint)
            }
    }
}

private fun retroSystemPercentText(value: Double?): String =
    value?.let { "${it.roundToInt()}%" } ?: "—"

@Composable
internal fun RetroSettingsArtworkPage(
    dashboardStyle: DashboardStyle,
    settings: AlarmSettings,
    ringtoneTitle: String,
    onDashboardStyleChange: (DashboardStyle) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBatteryEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onTestAlarm: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 7.dp, top = 18.dp, end = 7.dp, bottom = 5.dp)
    ) {
        val density = LocalDensity.current
        val topOffset = with(density) { 40.toDp() }
        val topToThemeGap = with(density) { 12.toDp() }
        val themeToAlarmGap = with(density) { 20.toDp() }
        val alarmToBatteryGap = with(density) { 20.toDp() }
        // Randul "Alarma baterie" intra in aceeasi socoteala de inaltime ca cardurile pictate, deci
        // pe un telefon scurt cardurile se micsoreaza putin, nu iese nimic din ecran.
        val batteryRowRatio = 0.13f
        val heightPerWidth =
            (361f / 1_380f) +
                (408f / 1_119f) +
                (1_077f / 960f) +
                batteryRowRatio
        val naturalWidth = maxWidth * 0.95f
        val availableHeight =
            (maxHeight - topOffset - topToThemeGap - themeToAlarmGap - alarmToBatteryGap)
                .coerceAtLeast(1.dp)
        val widthLimitedByHeight = availableHeight / heightPerWidth
        val cardWidth = if (naturalWidth <= widthLimitedByHeight) {
            naturalWidth
        } else {
            widthLimitedByHeight
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(x = 0, y = 40) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RetroSettingsTopArtwork(
                modifier = Modifier
                    .width(cardWidth)
                    .aspectRatio(1_380f / 361f)
            )
            Spacer(Modifier.height(topToThemeGap))
            RetroSettingsThemeArtwork(
                dashboardStyle = dashboardStyle,
                onDashboardStyleChange = onDashboardStyleChange,
                modifier = Modifier
                    .width(cardWidth)
                    .aspectRatio(1_119f / 408f)
            )
            Spacer(Modifier.height(themeToAlarmGap))
            RetroSettingsAlarmArtwork(
                settings = settings,
                ringtoneTitle = ringtoneTitle,
                onEnabledChange = onEnabledChange,
                onThresholdChange = onThresholdChange,
                onCooldownChange = onCooldownChange,
                onVibrateChange = onVibrateChange,
                onPickRingtone = onPickRingtone,
                onTestAlarm = onTestAlarm,
                modifier = Modifier
                    .width(cardWidth)
                    .aspectRatio(960f / 1_077f)
            )
            Spacer(Modifier.height(alarmToBatteryGap))
            RetroBatteryAlarmRow(
                enabled = settings.batteryEnabled,
                onEnabledChange = onBatteryEnabledChange,
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardWidth * batteryRowRatio)
            )
        }
    }
}

@Composable
private fun RetroSettingsTopArtwork(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.retro_settings_top_artwork),
        contentDescription = "Setari. Tema, alarma si sunet",
        modifier = modifier,
        contentScale = ContentScale.FillBounds
    )
}

@Composable
private fun RetroSettingsThemeArtwork(
    dashboardStyle: DashboardStyle,
    onDashboardStyleChange: (DashboardStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val scaleX = maxWidth / 1_119f
        val scaleY = maxHeight / 408f
        Image(
            painter = painterResource(R.drawable.retro_settings_theme_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        RetroEnergyTouchTarget(
            description = "Tema Retro",
            selected = dashboardStyle == DashboardStyle.RETRO,
            accent = RetroSage,
            modifier = Modifier
                .offset(x = scaleX * 75f, y = scaleY * 158f)
                .width(scaleX * 470f)
                .height(scaleY * 174f),
            onClick = { onDashboardStyleChange(DashboardStyle.RETRO) }
        )
        RetroEnergyTouchTarget(
            description = "Tema Simple",
            selected = dashboardStyle == DashboardStyle.SIMPLE,
            accent = RetroHouseBlue,
            modifier = Modifier
                .offset(x = scaleX * 575f, y = scaleY * 158f)
                .width(scaleX * 470f)
                .height(scaleY * 174f),
            onClick = { onDashboardStyleChange(DashboardStyle.SIMPLE) }
        )
    }
}

@Composable
private fun RetroSettingsAlarmArtwork(
    settings: AlarmSettings,
    ringtoneTitle: String,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onPickRingtone: () -> Unit,
    onTestAlarm: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription = "Setari alarma consum mare"
        }
    ) {
        val scaleX = maxWidth / 960f
        val scaleY = maxHeight / 1_077f

        Image(
            painter = painterResource(R.drawable.retro_settings_alarm_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        RetroSettingsAnimatedToggle(
            checked = settings.enabled,
            description = if (settings.enabled) "Alarma consum mare activa" else "Alarma consum mare oprita",
            modifier = Modifier
                .offset(x = scaleX * 710f, y = scaleY * 30f)
                .width(scaleX * 210f)
                .height(scaleY * 125f),
            onClick = { onEnabledChange(!settings.enabled) }
        )
        RetroSettingsStatusText(
            text = if (settings.enabled) "ACTIVĂ" else "OPRITĂ",
            color = if (settings.enabled) RetroSage else RetroBrassLight,
            modifier = Modifier
                .offset(x = scaleX * 140f, y = scaleY * 132f)
                .width(scaleX * 205f)
                .height(scaleY * 63f)
        )

        RetroSettingsSlider(
            value = settings.thresholdW,
            range = 0f..10_000f,
            step = 100,
            description = "Prag alarma ${settings.thresholdW} W",
            modifier = Modifier
                .offset(x = scaleX * 155f, y = scaleY * 293f)
                .width(scaleX * 780f)
                .height(scaleY * 125f),
            thumbTrackModifier = Modifier
                .offset(x = scaleX * 170f, y = scaleY * 303f)
                .width(scaleX * 730f)
                .height(scaleY * 92f),
            onValueChange = onThresholdChange
        )
        RetroSettingsValuePatch(
            text = "${settings.thresholdW} W",
            modifier = Modifier
                .offset(x = scaleX * 650f, y = scaleY * 242f)
                .width(scaleX * 220f)
                .height(scaleY * 58f),
            drawBackground = false
        )

        RetroSettingsSlider(
            value = settings.cooldownS,
            range = 0f..600f,
            step = 30,
            description = "Cooldown ${settings.cooldownS} secunde",
            modifier = Modifier
                .offset(x = scaleX * 155f, y = scaleY * 510f)
                .width(scaleX * 780f)
                .height(scaleY * 125f),
            thumbTrackModifier = Modifier
                .offset(x = scaleX * 170f, y = scaleY * 520f)
                .width(scaleX * 730f)
                .height(scaleY * 88f),
            onValueChange = onCooldownChange
        )
        RetroSettingsValuePatch(
            text = "${settings.cooldownS} s",
            modifier = Modifier
                .offset(x = scaleX * 690f, y = scaleY * 462f)
                .width(scaleX * 180f)
                .height(scaleY * 54f),
            drawBackground = false
        )

        RetroSettingsAnimatedToggle(
            checked = settings.vibrate,
            description = if (settings.vibrate) "Vibratie activa" else "Vibratie oprita",
            modifier = Modifier
                .offset(x = scaleX * 705f, y = scaleY * 666f)
                .width(scaleX * 215f)
                .height(scaleY * 130f),
            onClick = { onVibrateChange(!settings.vibrate) }
        )

        Text(
            text = ringtoneTitle.uppercase(Locale.getDefault()),
            color = RetroSage,
            fontFamily = RetroMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(x = scaleX * 220f, y = scaleY * 875f)
                .width(scaleX * 360f)
        )
        RetroSettingsClickTarget(
            description = "Alege sunet alarma, $ringtoneTitle",
            modifier = Modifier
                .offset(x = scaleX * 178f, y = scaleY * 850f)
                .width(scaleX * 470f)
                .height(scaleY * 125f),
            onClick = onPickRingtone
        )
        RetroSettingsClickTarget(
            description = "Testeaza alarma",
            modifier = Modifier
                .offset(x = scaleX * 650f, y = scaleY * 850f)
                .width(scaleX * 270f)
                .height(scaleY * 135f),
            onClick = onTestAlarm
        )
    }
}

@Composable
private fun RetroSettingsSlider(
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    description: String,
    modifier: Modifier,
    thumbTrackModifier: Modifier,
    onValueChange: (Int) -> Unit
) {
    val targetFraction = ((value - range.start) / (range.endInclusive - range.start))
        .coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "$description pozitie"
    )
    RetroSettingsArtworkSliderThumb(
        fraction = animatedFraction,
        modifier = thumbTrackModifier
    )
    Slider(
        value = value.toFloat().coerceIn(range.start, range.endInclusive),
        onValueChange = { raw ->
            val rounded = (raw / step).roundToInt() * step
            onValueChange(
                rounded.coerceIn(
                    range.start.roundToInt(),
                    range.endInclusive.roundToInt()
                )
            )
        },
        valueRange = range,
        modifier = modifier
            .alpha(0.01f)
            .semantics { contentDescription = description },
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

@Composable
private fun RetroSettingsArtworkSliderThumb(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val knobHeight = maxHeight
        val knobWidth = knobHeight * (100f / 282f)
        val travel = (maxWidth - knobWidth).coerceAtLeast(0.dp)
        Image(
            painter = painterResource(R.drawable.retro_settings_slider_knob),
            contentDescription = null,
            modifier = Modifier
                .offset(x = travel * fraction)
                .width(knobWidth)
                .height(knobHeight),
            contentScale = ContentScale.FillBounds
        )
    }
}

@Composable
internal fun RetroSettingsAnimatedToggle(
    checked: Boolean,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val transition = updateTransition(
        targetState = checked,
        label = "$description tranzitie"
    )
    val progress by transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = 260, easing = FastOutSlowInEasing)
        },
        label = "$description culisare"
    ) { active ->
        if (active) 1f else 0f
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(400f / 137f)
        ) {
            Image(
                painter = painterResource(R.drawable.retro_settings_toggle_track),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                val knobSize = maxHeight * (104f / 137f)
                val startX = maxWidth * (34f / 400f)
                val endX = maxWidth * (286f / 400f)
                val knobX = startX + (endX - startX) * progress
                val knobY = (maxHeight - knobSize) / 2f
                val glowSize = knobSize * (64f / 104f)
                val moving = sin(progress * PI).toFloat().coerceAtLeast(0f)

                Box(
                    modifier = Modifier
                        .offset(x = knobX, y = knobY)
                        .width(knobSize)
                        .height(knobSize),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = RetroSage.copy(
                                alpha = 0.08f * progress + 0.18f * moving
                            ),
                            radius = size.minDimension * (0.52f + 0.08f * moving)
                        )
                    }
                    Image(
                        painter = painterResource(R.drawable.retro_settings_toggle_knob),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    Image(
                        painter = painterResource(R.drawable.retro_settings_toggle_glow),
                        contentDescription = null,
                        modifier = Modifier
                            .width(glowSize)
                            .height(glowSize)
                            .alpha(progress),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroSettingsStatusText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = RetroMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun RetroSettingsValuePatch(
    text: String,
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (drawBackground) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF252113), Color(0xFF111209), Color(0xFF272113))
                    ),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }
        Text(
            text = text,
            color = RetroSage,
            fontFamily = RetroMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RetroSettingsClickTarget(
    description: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
    )
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 7.dp, end = 7.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .aspectRatio(1_835f / 321f)
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
        val scale = maxWidth / 1_386f
        Image(
            painter = painterResource(R.drawable.retro_dashboard_live_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Text(
            text = "V${BuildConfig.VERSION_NAME}",
            modifier = Modifier
                .offset(x = scale * 88f, y = scale * 23f)
                .offset { IntOffset(x = 8, y = -12) },
            color = Color(0xFFC9BC93),
            fontFamily = RetroMono,
            fontSize = 8.75.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.35.sp
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = scale * 23f, end = scale * 111f)
                .offset { IntOffset(x = 0, y = -7) },
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
                .offset(x = scale * 483f, y = scale * 579f)
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
                .offset(x = scale * 210f, y = scale * 879f)
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
                .offset(x = scale * 580f, y = scale * 879f)
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
                .offset(x = scale * 862f, y = scale * 812f)
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
        val center = Offset(size.width * 0.50f, size.height * 0.512f)
        val fraction = (animatedValue / 7_000f).coerceIn(0f, 1f)
        val angle = (155f + 230f * fraction) * PI.toFloat() / 180f
        val direction = Offset(cos(angle), sin(angle))
        val perpendicular = Offset(-direction.y, direction.x)
        val length = size.width * 0.287f
        val tip = center + direction * length
        val tail = center - direction * (size.width * 0.019f)
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
    val grid = (data?.gridImport ?: 0.0) + (data?.gridCharge ?: 0.0)
    val flow = resolveRetroEnergyFlow(
        pv = pv,
        house = data?.house ?: 0.0,
        batteryDisplay = battery,
        batteryCharge = data?.batteryCharge ?: 0.0,
        batterySupport = data?.batterySupport ?: 0.0,
        gridImport = data?.gridImport ?: 0.0,
        gridCharge = data?.gridCharge ?: 0.0
    )
    val batteryColor = retroBatteryFlowColor(flow.battery)
    val batteryAction = when (flow.battery) {
        RetroBatteryFlow.CHARGING -> "incarcare"
        RetroBatteryFlow.DISCHARGING -> "descarcare"
        RetroBatteryFlow.IDLE -> "repaus"
    }
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
        val scale = maxWidth / 1_405f
        Image(
            painter = painterResource(R.drawable.retro_dashboard_flow_artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Canvas(Modifier.fillMaxSize()) {
            fun cubicPoint(start: Offset, control1: Offset, control2: Offset, end: Offset, t: Float): Offset {
                val inverse = 1f - t
                return start * (inverse * inverse * inverse) +
                    control1 * (3f * inverse * inverse * t) +
                    control2 * (3f * inverse * t * t) +
                    end * (t * t * t)
            }

            fun cubicTangent(start: Offset, control1: Offset, control2: Offset, end: Offset, t: Float): Offset {
                val inverse = 1f - t
                return (control1 - start) * (3f * inverse * inverse) +
                    (control2 - control1) * (6f * inverse * t) +
                    (end - control2) * (3f * t * t)
            }

            fun led(point: Offset, perpendicular: Offset, index: Int, color: Color) {
                val jitter = sin((index + 1) * 4.13f + phase * 6.7f) * 1.05.dp.toPx()
                val center = point + perpendicular * jitter
                val irregularity = 0.84f + 0.16f * sin((index + 2) * 2.47f + phase * 5.1f)
                drawCircle(color.copy(alpha = 0.055f), 8.dp.toPx() * irregularity, center)
                drawCircle(color.copy(alpha = 0.16f), 4.5.dp.toPx() * irregularity, center)
                drawCircle(color.copy(alpha = 0.92f), 2.05.dp.toPx() * irregularity, center)
                drawCircle(
                    Color.White.copy(alpha = 0.52f),
                    0.58.dp.toPx(),
                    center - Offset(0.45.dp.toPx(), 0.50.dp.toPx())
                )
            }

            fun movingLeds(start: Offset, end: Offset, active: Boolean, color: Color) {
                if (!active) return
                val vector = end - start
                val length = sqrt(vector.x * vector.x + vector.y * vector.y).coerceAtLeast(1f)
                val perpendicular = Offset(-vector.y / length, vector.x / length)
                repeat(3) { index ->
                    val progress = (phase + index / 3f) % 1f
                    val point = start + vector * progress
                    led(point, perpendicular, index, color)
                }
            }

            fun physicalCableAndLeds(
                start: Offset,
                control1: Offset,
                control2: Offset,
                end: Offset,
                active: Boolean,
                color: Color
            ) {
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
                }
                drawPath(path, Color.Black.copy(alpha = 0.68f), style = Stroke(5.2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, RetroBrassDark.copy(alpha = 0.96f), style = Stroke(3.1.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, RetroBrassLight.copy(alpha = 0.66f), style = Stroke(0.85.dp.toPx(), cap = StrokeCap.Round))

                // Inele neregulate: cablu impletit/patinat, nu o linie vectoriala perfecta.
                repeat(17) { index ->
                    val t = (index + 1f) / 18f
                    val point = cubicPoint(start, control1, control2, end, t)
                    val tangent = cubicTangent(start, control1, control2, end, t)
                    val length = sqrt(tangent.x * tangent.x + tangent.y * tangent.y).coerceAtLeast(1f)
                    val perpendicular = Offset(-tangent.y / length, tangent.x / length)
                    val half = (1.15f + 0.28f * sin(index * 2.31f)) * density
                    drawLine(
                        color = Color.Black.copy(alpha = if (index % 3 == 0) 0.42f else 0.25f),
                        start = point - perpendicular * half,
                        end = point + perpendicular * half,
                        strokeWidth = 0.55.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                if (!active) return
                repeat(4) { index ->
                    val t = (phase + index / 4f) % 1f
                    val point = cubicPoint(start, control1, control2, end, t)
                    val tangent = cubicTangent(start, control1, control2, end, t)
                    val length = sqrt(tangent.x * tangent.x + tangent.y * tangent.y).coerceAtLeast(1f)
                    led(point, Offset(-tangent.y / length, tangent.x / length), index, color)
                }
            }

            val solarJoint = Offset(size.width * 0.450f, size.height * 0.345f)
            val batteryJoint = Offset(size.width * 0.230f, size.height * 0.711f)
            val houseLeft = Offset(size.width * 0.445f, size.height * 0.711f)
            val houseRight = Offset(size.width * 0.622f, size.height * 0.711f)
            val gridJoint = Offset(size.width * 0.783f, size.height * 0.711f)

            val solarHouseStart = Offset(size.width * 0.520f, size.height * 0.396f)
            val solarHouseControl1 = Offset(size.width * 0.516f, size.height * 0.450f)
            val solarHouseControl2 = Offset(size.width * 0.524f, size.height * 0.535f)
            val solarHouseEnd = Offset(size.width * 0.520f, size.height * 0.585f)

            physicalCableAndLeds(
                start = solarHouseStart,
                control1 = solarHouseControl1,
                control2 = solarHouseControl2,
                end = solarHouseEnd,
                active = flow.solarToHouse,
                color = RetroSage
            )
            movingLeds(solarJoint, batteryJoint, flow.solarToBattery, RetroSage)
            movingLeds(batteryJoint, houseLeft, flow.batteryToHouse, RetroYellow)
            movingLeds(gridJoint, houseRight, flow.gridToHouse, RetroRed)
            if (flow.gridToBattery) {
                movingLeds(houseRight, houseLeft, active = true, color = RetroRed)
                movingLeds(houseLeft, batteryJoint, active = true, color = RetroRed)
            }
        }

        RetroArtworkFlowValue(
            number = retroWhole(data?.pv),
            color = RetroSage,
            description = "Panouri ${retroWhole(data?.pv)} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick("pv_power") },
            modifier = Modifier
                .offset(x = scale * 800f, y = scale * 310f)
                .width(scale * 300f)
        )
        RetroArtworkFlowValue(
            number = retroSigned(data?.batteryDisplay),
            color = batteryColor,
            description = "Baterie ${retroSigned(data?.batteryDisplay)} W, $batteryAction. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick("battery_voltage") },
            modifier = Modifier
                .offset(x = scale * 73f, y = scale * 786f)
                .width(scale * 300f)
        )
        RetroArtworkFlowValue(
            number = retroWhole(data?.house),
            color = RetroHouseBlue,
            description = "Casa ${retroWhole(data?.house)} W. Deschide graficul Energie.",
            onClick = { onEnergyFieldClick("output_power") },
            modifier = Modifier
                .offset(x = scale * 608f, y = scale * 786f)
                .width(scale * 290f)
        )
        RetroArtworkFlowValue(
            number = retroWhole(if (data == null) null else grid),
            color = RetroRed,
            description = "Retea ${retroWhole(if (data == null) null else grid)} W",
            modifier = Modifier
                .offset(x = scale * 1_053f, y = scale * 786f)
                .width(scale * 285f)
        )
    }
}

@Composable
private fun RetroArtworkFlowValue(
    number: String,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier = modifier
            .height(30.dp)
            .then(clickModifier)
            .semantics { contentDescription = description }
    ) {
        RetroVfdDisplay(
            value = number,
            unit = "W",
            color = color,
            modifier = Modifier.fillMaxSize(),
            embedded = true,
            description = description
        )
    }
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
                supporting = data?.let { "${retroSigned(it.batteryDisplay)} W" } ?: "ASTEPT DATE",
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

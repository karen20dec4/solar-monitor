package com.rolling7.solar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cele doua pozitii ale comutatorului de pe fila SISTEM. */
internal const val RetroSubSystem = "SISTEM"
internal const val RetroSubCasa = "CASA"

internal val CasaOrange = Color(0xFFF5A640)
internal val CasaGreenLit = Color(0xFF6F9A28)
internal val CasaGreenDark = Color(0xFF2C3E0D)

/** Peste 20 s fara un raspuns bun la `/plugs` valorile devin liniute. */
internal const val PlugsStaleMs = 20_000L
internal const val PlugsPollMs = 3_000L
/** Pana la atatea prize randurile au inaltime fixa (compacta); peste, isi impart cardul. */
internal const val CompactPlugRows = 7
internal val CompactPlugRowHeight = 54.dp
private const val SensorsPollMs = 60_000L
internal const val ErrorShownMs = 4_000L
private const val MaxBadgeLines = 3

// ---------------------------------------------------------------------------------------------
// Ajutoare pure (testate in CasaRetroTest)
// ---------------------------------------------------------------------------------------------

internal enum class CasaBadgeKind { SCHEDULE, TIMER, LOOP, OFFLINE, MORE }

internal data class CasaBadge(val kind: CasaBadgeKind, val text: String)

private val ScheduleBadge = Regex("^(PORNESTE|OPRESTE)( (L|MA|MI|J|V|SA|DU))? \\d\\d:\\d\\d$")

/**
 * Randurile de sub numele prizei (max [maxLines]): "OFFLINE" daca nu raspunde, apoi insemnele din
 * [plugBadges] ("OPRESTE 17:00", "PORNESTE SA 09:00", "INCHING: STINGE DUPA 30 MIN").
 * Daca nu incap, ultimul rand spune cate au ramas ascunse.
 */
internal fun casaBadgeLines(
    p: CasaPlug, nowEpoch: Long, zone: ZoneId, maxLines: Int = MaxBadgeLines
): List<CasaBadge> {
    val all = ArrayList<CasaBadge>()
    if (!p.online) all += CasaBadge(CasaBadgeKind.OFFLINE, "OFFLINE")
    plugBadges(p, nowEpoch, zone).forEach { b ->
        all += when {
            ScheduleBadge.matches(b) -> CasaBadge(CasaBadgeKind.SCHEDULE, b)
            b.startsWith("AUTO ") -> CasaBadge(CasaBadgeKind.LOOP, "INCHING: STINGE DUPA ${b.removePrefix("AUTO ")}")
            else -> CasaBadge(CasaBadgeKind.TIMER, b)
        }
    }
    if (all.size <= maxLines) return all
    val hidden = all.size - (maxLines - 1)
    return all.take(maxLines - 1) + CasaBadge(CasaBadgeKind.MORE, "+$hidden ALTELE")
}

/** Cate prize merg si cati W trag impreuna (doar cele pornite, online, cu masurare). */
internal fun casaPlugsSummary(plugs: List<CasaPlug>, isOn: (CasaPlug) -> Boolean = { it.on }): Pair<Int, Int> {
    val running = plugs.filter { isOn(it) && it.online }
    val watts = running.filter { it.metered }.sumOf { it.power ?: 0.0 }
    return running.size to watts.roundToInt()
}

internal fun casaSummaryText(count: Int, watts: Int): String =
    "$count ${if (count == 1) "PORNITA" else "PORNITE"} · $watts W"

/** Culoarea cifrelor unui senzor: albastru sub 10 grade, galben peste 28, altfel verde. */
internal fun casaTemperatureColor(t: Double?): Color = when {
    t == null -> RetroMuted
    t < 10.0 -> RetroHouseBlue
    t > 28.0 -> RetroYellow
    else -> Color(0xFFA6EE62)
}

/** Titlul de pe placa senzorului: fara prefixul "TEMP " (placa e ingusta), daca mai ramane ceva. */
internal fun casaSensorTitle(name: String): String {
    val label = retroLabel(name)
    return label.removePrefix("TEMP ").trim().ifEmpty { label }
}

internal fun casaTemperatureText(t: Double?): String =
    if (t == null) "—" else String.format(Locale.US, "%.1f", t)

internal fun casaClockText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Europe/Bucharest"))
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable
internal fun fixedSp(v: Float): TextUnit = with(LocalDensity.current) { v.dp.toSp() }

/**
 * Material3 `Text` mosteneste din tema un `lineHeight` de 20-24 sp (x1.3 la fontul de 130 %), care umfla
 * fiecare rand si impingea textele din cardurile cu inaltime fixa afara din ele. Pe paginile CASA randul
 * ia inaltimea fontului.
 */
@Composable
internal fun CasaTextStyle(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
        content = content
    )
}

// ---------------------------------------------------------------------------------------------
// Gazda filei SISTEM si comutatorul SISTEM | CASA
// ---------------------------------------------------------------------------------------------

/**
 * Fila SISTEM: pagina veche sau pagina CASA, dupa [sub]. Gazda tine si priza / senzorul deschis
 * (ecranele de detaliu din RetroCasaDetail.kt).
 */
@Composable
internal fun RetroSystemHost(
    data: SolarData?,
    bms: BmsState?,
    onEnergyFieldClick: (String) -> Unit,
    sub: String,
    onSubChange: (String) -> Unit
) {
    var openPlugId by rememberSaveable { mutableStateOf<String?>(null) }
    var openSensorId by rememberSaveable { mutableStateOf<String?>(null) }
    val plugId = openPlugId
    val sensorId = openSensorId
    when {
        // Detaliul acopera pagina (bara de jos ramane); pagina nu mai ruleaza cat e deschis.
        sub == RetroSubCasa && plugId != null -> RetroPlugDetail(plugId) { openPlugId = null }
        sub == RetroSubCasa && sensorId != null -> RetroSensorDetail(sensorId) { openSensorId = null }
        sub == RetroSubCasa -> RetroCasaPage(
            sub = sub,
            onSubChange = onSubChange,
            onOpenPlug = { openPlugId = it },
            onOpenSensor = { openSensorId = it }
        )
        else -> RetroSystemPage(
            data = data,
            bms = bms,
            onEnergyFieldClick = onEnergyFieldClick,
            onSubChange = onSubChange
        )
    }
}

/**
 * Comutator cu doua pozitii "SISTEM | CASA"; jumatatea aleasa e aprinsa verde. Vizual are ~34 dp,
 * zona de atingere a fiecarei jumatati ocupa toata inaltimea data (>= 48 dp).
 */
@Composable
internal fun RetroCasaSelector(
    sub: String,
    onSubChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pill = RoundedCornerShape(50)
    val edge = Color(0xFF8A6A2E)
    CasaTextStyle { Box(modifier) {
        Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(34.dp)
                .clip(pill)
                .background(Color(0xFF0C0F07))
                .border(2.dp, edge, pill)
        ) {
            listOf(RetroSubSystem, RetroSubCasa).forEach { name ->
                val lit = name == sub
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (lit) {
                                Modifier.background(
                                    Brush.verticalGradient(listOf(CasaGreenLit, Color(0xFF3E5614), CasaGreenDark))
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (lit) Color(0xFFF2FFD0) else Color(0xFFA89860),
                        fontFamily = RetroMono,
                        fontSize = fixedSp(13f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = fixedSp(1.2f),
                        maxLines = 1
                    )
                }
            }
        }
        Row(Modifier.matchParentSize()) {
            listOf(RetroSubSystem, RetroSubCasa).forEach { name ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { contentDescription = if (name == sub) "$name, pagina curenta" else "Deschide $name" }
                        .clickable { if (name != sub) onSubChange(name) }
                )
            }
        }
    } }
}

// ---------------------------------------------------------------------------------------------
// Pagina CASA
// ---------------------------------------------------------------------------------------------

/** Ceasul de perete (Europe/Bucharest) al paginii; se reciteste doar in frunze, ca sa nu refaca pagina. */
@Composable
internal fun rememberCasaClock(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    WhileVisibleEffect {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

@Composable
internal fun RetroCasaPage(
    sub: String,
    onSubChange: (String) -> Unit,
    onOpenPlug: (String) -> Unit,
    onOpenSensor: (String) -> Unit
) {
    val context = LocalContext.current
    val access = remember { casaAccess(context) }
    var token by remember { mutableStateOf(access.token) }
    val clock = rememberCasaClock()

    CasaTextStyle { Column(
        Modifier
            .fillMaxSize()
            .padding(start = 7.dp, top = 12.dp, end = 7.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CasaHeader(sub = sub, onSubChange = onSubChange, clock = clock)
        if (token == null) {
            RetroCasaPasswordPlate(onRegistered = { token = access.token })
        } else {
            CasaContent(
                access = access,
                token = token,
                clock = clock,
                onTokenLost = { token = access.token },
                onOpenPlug = onOpenPlug,
                onOpenSensor = onOpenSensor,
                modifier = Modifier.weight(1f)
            )
        }
    } }
}

@Composable
private fun CasaHeader(sub: String, onSubChange: (String) -> Unit, clock: State<Long>) {
    RetroPanelSurface(
        contentPadding = PaddingValues(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "CASA",
                    color = RetroText,
                    fontFamily = RetroMono,
                    fontSize = fixedSp(25f),
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = fixedSp(3f),
                    maxLines = 1
                )
                Text(
                    text = "PRIZE SI SENZORI",
                    color = Color(0xFF7FBF4A),
                    fontFamily = RetroMono,
                    fontSize = fixedSp(10f),
                    letterSpacing = fixedSp(1.2f),
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                RetroCasaSelector(
                    sub = sub,
                    onSubChange = onSubChange,
                    modifier = Modifier.width(178.dp).height(48.dp)
                )
                Text(
                    text = casaClockText(clock.value),
                    color = Color(0xFF8BD84A),
                    fontFamily = RetroMono,
                    fontSize = fixedSp(11f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = fixedSp(1.3f),
                    maxLines = 1,
                    modifier = Modifier.offset(y = (-4).dp).padding(end = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CasaContent(
    access: CasaAccess,
    token: String?,
    clock: State<Long>,
    onTokenLost: () -> Unit,
    onOpenPlug: (String) -> Unit,
    onOpenSensor: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var plugs by remember { mutableStateOf<List<CasaPlug>>(emptyList()) }
    var sensors by remember { mutableStateOf<CasaSensors?>(null) }
    var lastGoodPlugsMs by remember { mutableLongStateOf(0L) }
    // Starea dorita de utilizator pana raspunde priza: id -> pornit?
    var optimistic by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val freshness = remember { CasaFreshness() }
    var switchError by remember { mutableStateOf<String?>(null) }
    var errorStamp by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val hapticContext = LocalContext.current
    val plugsStale by remember { derivedStateOf { clock.value - lastGoodPlugsMs > PlugsStaleMs } }

    fun lose(usedToken: String, r: CasaResult<*>) {
        access.onResult(usedToken, r)
        onTokenLost()
    }

    WhileVisibleEffect(token) {
        val t = token ?: return@WhileVisibleEffect
        while (true) {
            val ticket = freshness.beginPoll()
            val r = withContext(Dispatchers.IO) { CasaRepository.plugs(t) }
            when (r) {
                is CasaResult.Ok -> {
                    // o sondare pornita inainte de sfarsitul unei comenzi aduce starea veche
                    if (freshness.isFresh(ticket)) plugs = r.value
                    lastGoodPlugsMs = System.currentTimeMillis()
                }
                is CasaResult.Unauthorized -> {
                    lose(t, r)
                    return@WhileVisibleEffect
                }
                is CasaResult.Error -> Unit
            }
            delay(PlugsPollMs)
        }
    }
    WhileVisibleEffect(token) {
        val t = token ?: return@WhileVisibleEffect
        while (true) {
            val r = withContext(Dispatchers.IO) { CasaRepository.sensors(t) }
            when (r) {
                is CasaResult.Ok -> sensors = r.value
                is CasaResult.Unauthorized -> {
                    lose(t, r)
                    return@WhileVisibleEffect
                }
                is CasaResult.Error -> Unit
            }
            delay(SensorsPollMs)
        }
    }

    fun toggle(p: CasaPlug) {
        val t = token ?: return
        if (p.id in optimistic) return
        val target = !(optimistic[p.id] ?: p.on)
        optimistic = optimistic + (p.id to target)
        freshness.commandStarted()
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { CasaRepository.switch(t, p.id, target) }
            } finally {
                freshness.commandFinished()
            }
            optimistic = optimistic - p.id
            when (r) {
                is CasaResult.Ok -> {
                    CasaHaptics.confirm(hapticContext)
                    plugs = plugs.map { if (it.id == r.value.id) r.value else it }
                    lastGoodPlugsMs = System.currentTimeMillis()
                }
                is CasaResult.Unauthorized -> lose(t, r)
                is CasaResult.Error -> {
                    CasaHaptics.reject(hapticContext)
                    switchError = r.message
                    val stamp = System.currentTimeMillis()
                    errorStamp = stamp
                    launch {
                        delay(ErrorShownMs)
                        if (errorStamp == stamp) switchError = null
                    }
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CasaPlugsCard(
            plugs = plugs,
            stale = plugsStale,
            optimistic = optimistic,
            error = switchError,
            onToggle = ::toggle,
            onOpenPlug = onOpenPlug,
            modifier = Modifier.weight(1f)
        )
        CasaSensorsSection(sensors = sensors, clock = clock, onOpenSensor = onOpenSensor)
    }
}

@Composable
internal fun CasaSectionTitle(
    title: String,
    sub: String,
    subColor: Color,
    titleSize: Float,
    modifier: Modifier = Modifier
) {
    val line = RetroBrassLight.copy(alpha = 0.40f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            text = title,
            color = RetroText,
            fontFamily = RetroMono,
            fontSize = fixedSp(titleSize),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = fixedSp(2f),
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(
            text = sub,
            color = subColor,
            fontFamily = RetroMono,
            fontSize = fixedSp(titleSize - 3f),
            letterSpacing = fixedSp(0.6f),
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp, end = 8.dp)
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

@Composable
private fun CasaPlugsCard(
    plugs: List<CasaPlug>,
    stale: Boolean,
    optimistic: Map<String, Boolean>,
    error: String?,
    onToggle: (CasaPlug) -> Unit,
    onOpenPlug: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    RetroPanelSurface(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        fillContent = true
    ) {
        val (count, watts) = casaPlugsSummary(plugs) { optimistic[it.id] ?: it.on }
        when {
            error != null -> CasaSectionTitle("PRIZE", error, RetroRed, 14f)
            plugs.isEmpty() || stale -> CasaSectionTitle("PRIZE", "ASTEPT DATE", RetroYellow, 14f)
            else -> CasaSectionTitle("PRIZE", casaSummaryText(count, watts), RetroMuted, 14f)
        }
        // Randuri compacte de inaltime fixa (v3.43): pe Note 9 intra cel putin 7 prize; restul cardului ramane
        // liber pentru prize noi. Peste CompactPlugRows randurile isi impart inaltimea, ca pagina sa nu deruleze.
        val compact = plugs.size <= CompactPlugRows
        plugs.forEachIndexed { i, p ->
            if (i > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(RetroBrassLight.copy(alpha = 0.28f)))
            }
            CasaPlugRow(
                plug = p,
                on = optimistic[p.id] ?: p.on,
                stale = stale,
                busy = p.id in optimistic,
                onToggle = { onToggle(p) },
                onOpen = { onOpenPlug(p.id) },
                modifier = if (compact) Modifier.height(CompactPlugRowHeight) else Modifier.weight(1f)
            )
        }
        if (compact || plugs.isEmpty()) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CasaPlugRow(
    plug: CasaPlug,
    on: Boolean,
    stale: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val online = plug.online
    val usable = online && !stale
    val badges = casaBadgeLines(plug, System.currentTimeMillis() / 1000, BucharestZone, maxLines = 2)
    val power = plug.power
    val watts = if (usable && plug.metered && power != null) power.roundToInt().toString() else "---"
    val wattsColor = when {
        watts == "---" -> RetroSage.copy(alpha = 0.35f)
        on && power != null && power > 0.5 -> CasaOrange
        else -> RetroSage.copy(alpha = 0.55f)
    }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (online) 1f else 0.45f)
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = false) {
                contentDescription = "${retroLabel(plug.name)}, ${if (on) "pornita" else "oprita"}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RetroLed(
            color = RetroSage,
            active = on && online,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = retroLabel(plug.name),
                color = if (on) Color(0xFFF4ECC8) else Color(0xFFB9AD84),
                fontFamily = RetroMono,
                fontSize = fixedSp(13f),
                fontWeight = FontWeight.Bold,
                letterSpacing = fixedSp(0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            badges.forEach { CasaBadgeLine(it) }
        }
        // Prizele fara contor (Bucatarie, Lumina Curte) nu au placa de watti: numele si insignele iau locul.
        if (plug.metered) {
            CasaWattsPlate(
                value = watts,
                color = wattsColor,
                modifier = Modifier.width(76.dp).height(30.dp)
            )
        }
        RetroSettingsAnimatedToggle(
            checked = on,
            description = "${retroLabel(plug.name)}: ${if (on) "opreste" else "porneste"}",
            modifier = Modifier
                .width(58.dp)
                .height(42.dp)
                .alpha(if (!stale && !busy) 1f else 0.5f),
            // Si o priza raportata offline primeste comanda: serverul incearca oricum sa se conecteze.
            onClick = { if (!stale && !busy) onToggle() }
        )
    }
}

@Composable
private fun CasaBadgeLine(badge: CasaBadge) {
    val color = when (badge.kind) {
        CasaBadgeKind.SCHEDULE -> RetroBrassLight
        CasaBadgeKind.TIMER -> RetroHouseBlue
        CasaBadgeKind.LOOP -> RetroSage
        CasaBadgeKind.OFFLINE -> RetroRed
        CasaBadgeKind.MORE -> RetroMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (badge.kind != CasaBadgeKind.MORE) {
            Canvas(Modifier.size(10.dp)) { drawBadgeIcon(badge.kind, color) }
        }
        Text(
            text = badge.text,
            color = color,
            fontFamily = RetroMono,
            fontSize = fixedSp(9f),
            letterSpacing = fixedSp(0.2f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun DrawScope.drawBadgeIcon(kind: CasaBadgeKind, color: Color) {
    val s = size.minDimension
    val w = max(1f, s * 0.12f)
    when (kind) {
        CasaBadgeKind.SCHEDULE -> {
            drawCircle(color, radius = s * 0.44f, center = center, style = Stroke(w))
            drawLine(color, center, Offset(center.x, s * 0.22f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, center, Offset(s * 0.72f, center.y), strokeWidth = w, cap = StrokeCap.Round)
        }
        CasaBadgeKind.TIMER -> {
            val p = Path().apply {
                moveTo(s * 0.18f, s * 0.06f); lineTo(s * 0.82f, s * 0.06f)
                lineTo(s * 0.5f, s * 0.5f); close()
                moveTo(s * 0.18f, s * 0.94f); lineTo(s * 0.82f, s * 0.94f)
                lineTo(s * 0.5f, s * 0.5f); close()
            }
            drawPath(p, color, style = Stroke(w, join = StrokeJoin.Round))
        }
        CasaBadgeKind.LOOP -> {
            drawArc(
                color, startAngle = -40f, sweepAngle = 280f, useCenter = false,
                topLeft = Offset(s * 0.1f, s * 0.1f), size = Size(s * 0.8f, s * 0.8f),
                style = Stroke(w, cap = StrokeCap.Round)
            )
            val head = Path().apply {
                moveTo(s * 0.72f, s * 0.02f); lineTo(s * 0.98f, s * 0.30f); lineTo(s * 0.62f, s * 0.34f); close()
            }
            drawPath(head, color)
        }
        CasaBadgeKind.OFFLINE -> {
            drawLine(color, Offset(s * 0.15f, s * 0.15f), Offset(s * 0.85f, s * 0.85f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, Offset(s * 0.85f, s * 0.15f), Offset(s * 0.15f, s * 0.85f), strokeWidth = w, cap = StrokeCap.Round)
        }
        CasaBadgeKind.MORE -> Unit
    }
}

/** Placa metalica a wattilor: fereastra VFD (fotografia `dial_blank`) cu cifrele desenate nativ. */
@Composable
internal fun CasaWattsPlate(value: String, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = "$value W" }) {
        Image(
            painter = painterResource(R.drawable.retro_dashboard_dial_blank),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        RetroVfdDisplay(
            value = value,
            unit = "W",
            color = color,
            embedded = true,
            horizontalPadding = 3.dp,
            modifier = Modifier
                .offset(x = maxWidth * 0.11f, y = maxHeight * 0.16f)
                .width(maxWidth * 0.78f)
                .height(maxHeight * 0.70f),
            description = ""
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Senzori
// ---------------------------------------------------------------------------------------------

@Composable
private fun CasaSensorsSection(
    sensors: CasaSensors?,
    clock: State<Long>,
    onOpenSensor: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val sub = when {
            sensors == null -> "ASTEPT DATE"
            !sensors.cloudOk -> "CLOUD DECONECTAT"
            else -> "ULTIMELE 2H"
        }
        CasaSectionTitle(
            "SENZORI", sub,
            if (sensors != null && !sensors.cloudOk) RetroRed else RetroMuted, 11f
        )
        Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val list = sensors?.sensors.orEmpty().take(4)
            list.forEach { s ->
                CasaSensorPlate(
                    sensor = s,
                    cloudOk = sensors?.cloudOk == true,
                    clock = clock,
                    onClick = { onOpenSensor(s.id) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
            if (list.isEmpty()) {
                CasaPlateFrame(Modifier.fillMaxSize()) {
                    Text(
                        text = if (sensors == null) "ASTEPT DATE" else "FARA SENZORI",
                        color = RetroYellow,
                        fontFamily = RetroMono,
                        fontSize = fixedSp(11f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/**
 * Rama placii `dial_blank` in 9 felii: coltarele (cu suruburi) isi pastreaza forma, marginile si
 * centrul se intind. Fara asta placa, intinsa de la 2.5:1 la ~1.3:1, are suruburile ovale.
 */
@Composable
internal fun CasaPlateFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val bmp: ImageBitmap = ImageBitmap.imageResource(R.drawable.retro_dashboard_dial_blank)
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val cs = 60                                    // felia de colt in pixeli sursa
            val cd = cs * 0.30f * density                  // marimea ei pe ecran
            val sx = intArrayOf(0, cs, bmp.width - cs, bmp.width)
            val sy = intArrayOf(0, cs, bmp.height - cs, bmp.height)
            val dx = floatArrayOf(0f, cd, size.width - cd, size.width)
            val dy = floatArrayOf(0f, cd, size.height - cd, size.height)
            for (r in 0..2) for (c in 0..2) {
                drawImage(
                    image = bmp,
                    srcOffset = IntOffset(sx[c], sy[r]),
                    srcSize = IntSize(sx[c + 1] - sx[c], sy[r + 1] - sy[r]),
                    dstOffset = IntOffset(dx[c].roundToInt(), dy[r].roundToInt()),
                    dstSize = IntSize((dx[c + 1] - dx[c]).roundToInt(), (dy[r + 1] - dy[r]).roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .padding(start = 21.dp, end = 21.dp, top = 11.dp, bottom = 10.dp),
            content = content
        )
    }
}

@Composable
private fun CasaSensorPlate(
    sensor: CasaSensor,
    cloudOk: Boolean,
    clock: State<Long>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nowSec = clock.value / 1000
    val stale = isStale(nowSec, sensor.updated)
    val age = formatAge(nowSec, sensor.updated)
    val name = casaSensorTitle(sensor.name)
    Box(modifier.clickable(onClick = onClick)) {
        CasaPlateFrame(Modifier.fillMaxSize()) {
            if (!cloudOk) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, color = Color(0xFFE2D6A8), fontFamily = RetroMono, fontSize = fixedSp(10.5f),
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("CLOUD", color = RetroRed, fontFamily = RetroMono, fontSize = fixedSp(10f),
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("DECONECTAT", color = RetroRed, fontFamily = RetroMono, fontSize = fixedSp(10f),
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
                return@CasaPlateFrame
            }
            val color = casaTemperatureColor(sensor.temperature).let { if (stale) it.copy(alpha = 0.45f) else it }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth().height(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = Color(0xFFE2D6A8),
                        fontFamily = RetroMono,
                        fontSize = fixedSp(10.5f),
                        lineHeight = fixedSp(12f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = fixedSp(0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Canvas(Modifier.size(8.dp)) { drawDrop(RetroHouseBlue) }
                    Text(
                        text = sensor.humidity?.let { "${it.roundToInt()}%" } ?: "--%",
                        color = RetroHouseBlue,
                        fontFamily = RetroMono,
                        fontSize = fixedSp(9f),
                        lineHeight = fixedSp(11f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                Row(Modifier.height(26.dp), verticalAlignment = Alignment.Bottom) {
                    RetroVfdDisplay(
                        value = casaTemperatureText(sensor.temperature),
                        unit = "",
                        color = color,
                        embedded = true,
                        horizontalPadding = 0.dp,
                        modifier = Modifier.width(52.dp).height(26.dp),
                        description = ""
                    )
                    Text(
                        text = "°C",
                        color = Color(0xFFB6E68A).copy(alpha = 0.85f),
                        fontFamily = RetroMono,
                        fontSize = fixedSp(10f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                    )
                }
                CasaSparkline(sensor.spark, Modifier.fillMaxWidth().height(14.dp))
                Text(
                    text = age,
                    color = if (stale) RetroRed else RetroMuted,
                    fontFamily = RetroMono,
                    fontSize = fixedSp(8f),
                    lineHeight = fixedSp(9f),
                    fontWeight = if (stale) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

internal fun DrawScope.drawDrop(color: Color) {
    val s = size.minDimension
    val p = Path().apply {
        moveTo(s * 0.5f, 0f)
        cubicTo(s * 0.5f, 0f, s * 0.05f, s * 0.5f, s * 0.05f, s * 0.68f)
        cubicTo(s * 0.05f, s * 0.9f, s * 0.25f, s, s * 0.5f, s)
        cubicTo(s * 0.75f, s, s * 0.95f, s * 0.9f, s * 0.95f, s * 0.68f)
        cubicTo(s * 0.95f, s * 0.5f, s * 0.5f, 0f, s * 0.5f, 0f)
        close()
    }
    drawPath(p, color)
}

/** Ultimele 2 ore ca linie portocalie cu luminita; o linie plata ramane la mijloc, nu se "mareste" zgomotul. */
@Composable
private fun CasaSparkline(points: List<Pair<Long, Double>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val t0 = points.first().first
        val t1 = points.last().first
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val span = max(hi - lo, 1.0)
        val mid = (hi + lo) / 2.0
        val pad = 2.dp.toPx()
        val path = Path()
        points.forEachIndexed { i, (t, v) ->
            val x = if (t1 > t0) (t - t0).toFloat() / (t1 - t0) * size.width else size.width * i / (points.size - 1)
            val y = pad + (size.height - 2 * pad) * (0.5f - ((v - mid) / span).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, CasaOrange.copy(alpha = 0.28f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, CasaOrange, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------------------------
// Placa cu parola
// ---------------------------------------------------------------------------------------------

/**
 * "ACCES CASA": telefonul nu e inregistrat. Parola se schimba pe server cu un jeton propriu telefonului;
 * parola nu se salveaza nicaieri in aplicatie si nu ajunge in log.
 */
@Composable
internal fun RetroCasaPasswordPlate(onRegistered: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val access = remember { casaAccess(context) }
    val scope = rememberCoroutineScope()
    // Intentionat `remember`, nu rememberSaveable: parola nu intra in starea salvata a activitatii.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        if (password.isBlank()) {
            error = "INTRODU PAROLA"
            return
        }
        val typed = password
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { CasaRepository.register(typed, CasaRepository.deviceName()) }
            busy = false
            when (r) {
                is CasaResult.Ok -> {
                    access.save(r.value)
                    password = ""
                    onRegistered()
                }
                is CasaResult.Error -> error = r.message
                is CasaResult.Unauthorized -> error = "PAROLA GRESITA"
            }
        }
    }

    RetroPanelSurface(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ACCES CASA",
            color = RetroText,
            fontFamily = RetroMono,
            fontSize = fixedSp(20f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = fixedSp(3f),
            maxLines = 1
        )
        Text(
            text = "TELEFONUL NU ESTE INREGISTRAT. INTRODU PAROLA PENTRU A COMANDA PRIZELE SI A VEDEA SENZORII.",
            color = RetroMuted,
            fontFamily = RetroMono,
            fontSize = fixedSp(11f),
            lineHeight = fixedSp(15f)
        )
        RetroInsetSurface(Modifier.fillMaxWidth().height(52.dp)) {
            BasicTextField(
                value = password,
                onValueChange = { password = it; error = null },
                singleLine = true,
                readOnly = busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                textStyle = TextStyle(
                    color = RetroText,
                    fontFamily = RetroMono,
                    fontSize = fixedSp(18f),
                    letterSpacing = fixedSp(2f)
                ),
                cursorBrush = SolidColor(RetroSage),
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Parola CASA" },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        if (password.isEmpty()) {
                            Text(
                                text = "PAROLA",
                                color = RetroMuted.copy(alpha = 0.55f),
                                fontFamily = RetroMono,
                                fontSize = fixedSp(16f),
                                letterSpacing = fixedSp(2f)
                            )
                        }
                        inner()
                    }
                }
            )
        }
        Text(
            text = error ?: if (busy) "SE INREGISTREAZA..." else " ",
            color = if (error != null) RetroRed else RetroSage,
            fontFamily = RetroMono,
            fontSize = fixedSp(12f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        RetroMetalButton(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            selected = !busy,
            accent = RetroSage,
            description = "Inregistreaza telefonul",
            onClick = { submit() }
        ) {
            Text(
                text = "INREGISTREAZA",
                color = RetroText,
                fontFamily = RetroMono,
                fontSize = fixedSp(15f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = fixedSp(2f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

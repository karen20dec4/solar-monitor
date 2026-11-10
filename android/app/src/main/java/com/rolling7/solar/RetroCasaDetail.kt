package com.rolling7.solar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Ajutoare pure (testate in CasaDetailTest)
// ---------------------------------------------------------------------------------------------

internal const val InchingMinMinutes = 5
internal const val InchingMaxMinutes = 720
internal const val InchingStepMinutes = 5
internal const val CountdownMaxMinutes = 1440
private const val InchingDebounceMs = 600L
internal val BucharestZone: ZoneId = ZoneId.of("Europe/Bucharest")

/** Valoarea inchingului dupa un pas (-1 / +1): multiplu de 5, intre 5 si 720 minute. */
internal fun casaInchingStep(current: Int, direction: Int): Int {
    val base = (current.toDouble() / InchingStepMinutes).roundToInt() * InchingStepMinutes
    return (base + direction * InchingStepMinutes).coerceIn(InchingMinMinutes, InchingMaxMinutes)
}

/** Valoarea afisata / trimisa cand serverul spune 0 sau o valoare in afara intervalului. */
internal fun casaInchingClamp(minutes: Int): Int =
    if (minutes <= 0) 30 else minutes.coerceIn(InchingMinMinutes, InchingMaxMinutes)

/**
 * Amana trimiterea inchingului ([delayMs]) ca apasarile rapide pe -/+ sa produca o singura cerere.
 * [flush] trimite acum valoarea in asteptare (la inchiderea ecranului, ca sa nu se piarda); [cancel] o arunca
 * (cand alta comanda o preia). [send] primeste doar minutele: starea ramasa (pornit/oprit) se citeste de
 * apelant in momentul trimiterii, nu cand s-a apasat.
 */
internal class CasaInchingDebounce(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val delayMs: Long,
    private val send: (Int) -> Unit
) {
    @Volatile
    var pending: Int? = null
        private set
    private var job: Job? = null

    fun set(minutes: Int) {
        pending = minutes
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            fire()
        }
    }

    private fun fire() {
        val m = pending ?: return
        pending = null
        job = null
        send(m)
    }

    fun flush() {
        job?.cancel()
        fire()
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
    }
}

/** Scope care nu se opreste odata cu ecranul: trimite ultima valoare de inching la inchidere. */
private val CasaDetachedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Actiunea implicita a countdown-ului: opusul starii curente (pornita -> OPRESTE). */
internal fun casaCountdownDefaultAction(on: Boolean): String = if (on) "off" else "on"

internal fun casaCountdownTotal(hours: Int, minutes: Int): Int = hours * 60 + minutes

internal fun casaCountdownValid(hours: Int, minutes: Int): Boolean =
    casaCountdownTotal(hours, minutes) in 1..CountdownMaxMinutes

/** Ore si minute (rotunjite in sus) dintr-un numar de secunde ramase. */
internal fun casaDurationParts(seconds: Long): Pair<Int, Int> {
    val total = ceil(seconds.coerceAtLeast(0) / 60.0).toInt()
    return total / 60 to total % 60
}

/** "OPRIRE IN 23 MIN" / "OPRIRE IN 1 H 05 MIN". */
internal fun casaDeadlineText(seconds: Long): String {
    val (h, m) = casaDurationParts(seconds)
    return if (h > 0) "OPRIRE IN $h H ${m.toString().padStart(2, '0')} MIN" else "OPRIRE IN $m MIN"
}

internal fun casaHm(h: Int, m: Int): String = "%02d:%02d".format(Locale.US, h, m)

/**
 * "HH:MM" deplasat cu [dh] ore si [dm] minute, peste miezul noptii in ambele sensuri (23:59 + 1 min = 00:00,
 * 00:00 - 1 h = 23:00). Un minut care trece de :59 duce si ora. O ora stricata se citeste ca 00:00.
 */
internal fun casaStepTime(time: String, dh: Int, dm: Int): String {
    val h = time.substringBefore(':').toIntOrNull() ?: 0
    val m = time.substringAfter(':', "0").toIntOrNull() ?: 0
    val total = Math.floorMod(h * 60 + m + dh * 60 + dm, 1440)
    return casaHm(total / 60, total % 60)
}

/** Ora implicita a unui eveniment nou: ora fixa de dupa cea curenta (ora Bucurestiului), ex. 14:07 -> 15:00. */
internal fun casaDefaultEventTime(nowEpoch: Long, zone: ZoneId = BucharestZone): String {
    val now = Instant.ofEpochSecond(nowEpoch).atZone(zone)
    return casaHm((now.hour + 1) % 24, 0)
}

/** Zilele de trimis la server: null (zilnic) cand e ales ZILNIC sau toate cele 7 zile; altfel lista sortata. */
internal fun casaEventDays(daily: Boolean, picked: Set<Int>): List<Int>? =
    if (daily || picked.size == 7) null else picked.sorted()

/** Un eveniment se poate salva daca e zilnic sau are cel putin o zi aleasa. */
internal fun casaEventDaysValid(daily: Boolean, picked: Set<Int>): Boolean = daily || picked.isNotEmpty()

internal fun casaBatteryText(b: Double?): String =
    if (b == null) "BATERIE ?" else "BATERIE ${b.roundToInt()}%"

internal fun casaRangeSeconds(range: String): Long = when (range) {
    "2h" -> 7_200L
    "7d" -> 604_800L
    else -> 86_400L
}

internal fun casaRangeTitle(range: String): String = when (range) {
    "2h" -> "ULTIMELE 2 ORE"
    "7d" -> "ULTIMELE 7 ZILE"
    else -> "ULTIMELE 24H"
}

private val DayAbbrev = mapOf(
    DayOfWeek.MONDAY to "LU", DayOfWeek.TUESDAY to "MA", DayOfWeek.WEDNESDAY to "MI",
    DayOfWeek.THURSDAY to "JO", DayOfWeek.FRIDAY to "VI", DayOfWeek.SATURDAY to "SA",
    DayOfWeek.SUNDAY to "DU"
)

internal fun casaDayAbbrev(d: DayOfWeek): String = DayAbbrev.getValue(d)

internal data class CasaTick(val t: Long, val label: String)

private val HmFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Reperele de timp ale axei X pentru fereastra [now - interval, now]: 2h din 20 in 20 de minute, 1d din 4 in
 * 4 ore (HH:MM, ora Bucurestiului), 7d cate unul la fiecare miezul noptii (LU..DU). Cele lipite de margine
 * se omit, ca etichetele sa nu iasa din grafic.
 */
internal fun casaTimeTicks(range: String, nowSec: Long, zone: ZoneId = BucharestZone): List<CasaTick> {
    val span = casaRangeSeconds(range)
    val t0 = nowSec - span
    val margin = span * 0.03
    val out = ArrayList<CasaTick>()
    if (range == "7d") {
        var d = Instant.ofEpochSecond(t0).atZone(zone).toLocalDate().plusDays(1)
        while (true) {
            val t = d.atStartOfDay(zone).toEpochSecond()
            if (t > nowSec) break
            if (t - t0 >= margin && nowSec - t >= margin) out += CasaTick(t, casaDayAbbrev(d.dayOfWeek))
            d = d.plusDays(1)
        }
    } else {
        val step = if (range == "2h") 1_200L else 14_400L
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(t0)).totalSeconds
        var t = Math.floorDiv(t0 + offset, step) * step + step - offset
        while (t < nowSec) {
            if (t - t0 >= margin && nowSec - t >= margin) {
                out += CasaTick(t, Instant.ofEpochSecond(t).atZone(zone).format(HmFormat))
            }
            t += step
        }
    }
    return out
}

/** Momentul unui minim / maxim: "HH:MM" (2h, 1d) sau "LU 14:20" (7d). */
internal fun casaStatTime(range: String, epoch: Long, zone: ZoneId = BucharestZone): String {
    val z = Instant.ofEpochSecond(epoch).atZone(zone)
    val hm = z.format(HmFormat)
    return if (range == "7d") "${casaDayAbbrev(z.dayOfWeek)} $hm" else hm
}

/** Axa verticala: `divisions` intervale egale de `step` intre [lo] si [hi]. */
internal data class CasaAxis(val lo: Double, val hi: Double, val step: Double)

private val NiceSteps = listOf(0.5, 1.0, 2.0, 2.5, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0)

/**
 * Cea mai mica axa "rotunda" care cuprinde [minV]..[maxV] in [divisions] intervale. [minStep] opreste
 * zgomotul sa fie mărit pe tot graficul; [cap] limiteaza capatul de sus (100 % la umiditate).
 */
internal fun casaNiceAxis(
    minV: Double,
    maxV: Double,
    divisions: Int = 4,
    minStep: Double = 1.0,
    cap: Double? = null
): CasaAxis {
    val raw = maxOf((maxV - minV) / divisions, minStep)
    val candidates = NiceSteps.filter { it >= raw - 1e-9 }.ifEmpty { listOf(raw) }
    var chosen = candidates.last()
    var lo = floor(minV / chosen) * chosen
    for (step in candidates) {
        val l = floor(minV / step) * step
        if (l + divisions * step >= maxV - 1e-9) {
            chosen = step
            lo = l
            break
        }
    }
    var hi = lo + divisions * chosen
    if (cap != null && hi > cap) {
        hi = cap
        lo = cap - divisions * chosen
    }
    return CasaAxis(lo, hi, chosen)
}

internal fun casaAxisLabel(v: Double, step: Double, unit: String): String {
    val txt = if (step % 1.0 == 0.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    return txt + unit
}

/** Pozitia (0..1) a unui moment in fereastra [t0, t1]. */
internal fun casaChartFraction(t: Long, t0: Long, t1: Long): Float =
    if (t1 <= t0) 0f else ((t - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f)

// ---------------------------------------------------------------------------------------------
// Componente comune
// ---------------------------------------------------------------------------------------------

private val BrassEdge = Color(0xFF8A6A2E)
private val CasaBlue = Color(0xFF8FC8F0)
private val CasaGreenText = Color(0xFF7FBF4A)
private val CasaRedFillTop = Color(0xFF7A2418)
private val CasaRedFillBottom = Color(0xFF3E120C)

@Composable
private fun CasaText(
    text: String,
    size: Float,
    color: Color = RetroText,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    spacing: Float = 0.5f,
    align: TextAlign? = null,
    maxLines: Int = 1
) {
    Text(
        text = text,
        color = color,
        fontFamily = RetroMono,
        fontSize = fixedSp(size),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = fixedSp(spacing),
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Buton metalic cu text; [fill] umple fata butonului (verde pentru alegerea activa, rosu pentru ANULEAZA). */
@Composable
private fun CasaKey(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = RetroYellow,
    fill: Pair<Color, Color>? = null,
    enabled: Boolean = true,
    size: Float = 12f,
    description: String = text,
    onClick: () -> Unit
) {
    RetroMetalButton(
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        selected = selected,
        accent = accent,
        description = description,
        onClick = { if (enabled) onClick() }
    ) {
        val f = fill ?: if (selected) CasaGreenLit to CasaGreenDark else null
        if (f != null) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(f.first.copy(alpha = 0.80f), f.second.copy(alpha = 0.90f)))))
        }
        CasaText(
            text, size, if (f != null) Color(0xFFF2FFD0) else RetroText, Modifier.align(Alignment.Center),
            bold = true, spacing = 1f, align = TextAlign.Center
        )
    }
}

@Composable
private fun CasaCloseButton(onClose: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = "Inchide" }
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(38.dp)) {
            drawCircle(Color(0xFF1B170B))
            drawCircle(BrassEdge, style = Stroke(2.dp.toPx()))
            drawCircle(RetroBrassLight.copy(alpha = 0.35f), radius = size.minDimension / 2f - 3.dp.toPx(), style = Stroke(0.8.dp.toPx()))
            val a = size.minDimension * 0.30f
            val c = center
            val w = 3.dp.toPx()
            drawLine(RetroText, Offset(c.x - a, c.y - a), Offset(c.x + a, c.y + a), w, StrokeCap.Round)
            drawLine(RetroText, Offset(c.x - a, c.y + a), Offset(c.x + a, c.y - a), w, StrokeCap.Round)
        }
    }
}

/**
 * Titlul ecranului de detaliu + subtitlul (verde, sau rosu cand e o eroare) + butonul X. [subtitleTag] (ex.
 * "TASMOTA") se scrie la sfarsitul subtitlului, dupa un " · ", in auriu, oricare ar fi culoarea subtitlului.
 */
@Composable
private fun CasaDetailHeader(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onClose: () -> Unit,
    onRename: (() -> Unit)? = null,
    subtitleTag: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    RetroPanelSurface(contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Atingerea titlului deschide redenumirea (creionul arata ca se poate).
                Row(
                    modifier = if (onRename != null) {
                        Modifier.clickable(onClick = onRename).semantics { contentDescription = "Redenumeste $title" }
                    } else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CasaText(title, 24f, RetroText, Modifier.weight(1f, fill = false), bold = true, spacing = 3f)
                    if (onRename != null) {
                        Canvas(Modifier.size(16.dp)) { drawPencil(RetroBrassLight) }
                    }
                }
                if (subtitleTag == null) {
                    CasaText(subtitle, 10f, subtitleColor, spacing = 1f)
                } else {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = subtitleColor)) { append("$subtitle · ") }
                            withStyle(SpanStyle(color = RetroBrassLight, fontWeight = FontWeight.Bold)) { append(subtitleTag) }
                        },
                        fontFamily = RetroMono,
                        fontSize = fixedSp(10f),
                        letterSpacing = fixedSp(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            CasaCloseButton(onClose)
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}

private fun DrawScope.drawPencil(color: Color) {
    val s = size.minDimension
    val w = s * 0.12f
    drawLine(color, Offset(s * 0.18f, s * 0.82f), Offset(s * 0.78f, s * 0.22f), strokeWidth = w * 1.8f, cap = StrokeCap.Round)
    drawLine(color, Offset(s * 0.08f, s * 0.92f), Offset(s * 0.2f, s * 0.8f), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(s * 0.1f, s * 0.95f), Offset(s * 0.4f, s * 0.95f), strokeWidth = w * 0.7f, cap = StrokeCap.Round)
}

/** Redenumirea unei prize sau a unui senzor: numele se salveaza pe server, nu si in SmartLife. */
@Composable
private fun CasaRenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val clean = casaCleanName(text, current)
    CasaDialog("REDENUMESTE", onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF10110C))
                .border(1.dp, RetroBrassLight.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { if (it.length <= CasaNameMax) text = it },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { clean?.let(onSave) }),
                textStyle = TextStyle(color = RetroText, fontFamily = RetroMono, fontSize = fixedSp(16f)),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(RetroSage),
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Nume nou" },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) { inner() }
                }
            )
        }
        CasaText(
            "SE SCHIMBA IN APLICATIE SI GRAFANA, NU SI IN SMARTLIFE",
            9f, RetroMuted, Modifier.fillMaxWidth(), spacing = 0.5f, align = TextAlign.Center
        )
        CasaDialogButtons(okText = "SALVEAZA", okEnabled = clean != null, onCancel = onDismiss, onOk = { clean?.let(onSave) })
    }
}

@Composable
private fun CasaDetailFrame(content: @Composable ColumnScope.() -> Unit) {
    CasaTextStyle {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 7.dp, top = 10.dp, end = 7.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun CasaDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CasaTextStyle {
            RetroPanelSurface(
                modifier = Modifier.padding(horizontal = 18.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CasaText(title, 14f, RetroText, Modifier.fillMaxWidth(), bold = true, spacing = 2f, align = TextAlign.Center)
                content()
            }
        }
    }
}

@Composable
private fun CasaDialogButtons(
    okText: String,
    okEnabled: Boolean = true,
    onCancel: () -> Unit,
    onOk: () -> Unit,
    cancelText: String = "RENUNTA"
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CasaKey(cancelText, Modifier.weight(1f).height(44.dp), onClick = onCancel)
        CasaKey(okText, Modifier.weight(1f).height(44.dp), selected = true, enabled = okEnabled, onClick = onOk)
    }
}

@Composable
private fun CasaStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CasaText(label, 12f, RetroMuted, Modifier.width(70.dp), spacing = 1f)
        CasaKey("−", Modifier.size(48.dp), size = 18f, description = "Mai putin $label", onClick = onMinus)
        RetroInsetSurface(Modifier.weight(1f).height(48.dp)) {
            CasaText(value, 20f, RetroSage, Modifier.align(Alignment.Center), bold = true, spacing = 1f)
        }
        CasaKey("+", Modifier.size(48.dp), size = 18f, description = "Mai mult $label", onClick = onPlus)
    }
}

/** Ofera numai `RetroSettingsAnimatedToggle` cu dimensiuni potrivite pentru ecranul de detaliu. */
@Composable
private fun CasaToggle(
    checked: Boolean,
    description: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    RetroSettingsAnimatedToggle(
        checked = checked,
        description = description,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        onClick = { if (enabled) onClick() }
    )
}

// ---------------------------------------------------------------------------------------------
// D: detaliul prizei
// ---------------------------------------------------------------------------------------------

@Composable
internal fun RetroPlugDetail(plugId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val access = remember { casaAccess(context) }
    val token = remember { access.token }
    if (token == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
        return
    }
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val clock = rememberCasaClock()
    val lock = remember { Mutex() }
    val freshness = remember { CasaFreshness() }

    var plug by remember { mutableStateOf<CasaPlug?>(null) }
    var position by remember { mutableStateOf(0 to 0) }
    var missing by remember { mutableStateOf(false) }
    var recvMs by remember { mutableLongStateOf(0L) }
    var lastGoodMs by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageStamp by remember { mutableLongStateOf(0L) }
    var pendingOn by remember { mutableStateOf<Boolean?>(null) }
    var pendingEnabled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var pendingInching by remember { mutableStateOf<Int?>(null) }
    // Grupurile de comenzi aflate in zbor: "power", "countdown", "inching" (atingerile lor se ignora).
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var action by remember { mutableStateOf<String?>(null) }
    var showDuration by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // Editorul de evenimente (null = inchis) si evenimentul de sters (dupa confirmare).
    var editor by remember { mutableStateOf<CasaEventDraft?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CasaEvent?>(null) }
    val stale by remember { derivedStateOf { clock.value - lastGoodMs > PlugsStaleMs } }

    fun lose(r: CasaResult<*>) {
        access.onResult(token, r)
        onClose()
    }

    fun show(text: String) {
        val stamp = System.currentTimeMillis()
        message = text
        messageStamp = stamp
        scope.launch {
            delay(ErrorShownMs)
            if (messageStamp == stamp) message = null
        }
    }

    fun accept(p: CasaPlug) {
        // Raspunsul unei comenzi nu are energia de azi (vine doar la `GET /plugs`): pastram ultima valoare.
        plug = if (p.energyToday == null) p.copy(energyToday = plug?.energyToday) else p
        recvMs = System.currentTimeMillis()
        lastGoodMs = recvMs
    }

    WhileVisibleEffect(token) {
        while (true) {
            val ticket = freshness.beginPoll()
            val r = withContext(Dispatchers.IO) { CasaRepository.plugs(token) }
            when (r) {
                is CasaResult.Ok -> {
                    // Orice raspuns bun dovedeste legatura, si cat tine o comanda lunga.
                    lastGoodMs = System.currentTimeMillis()
                    val i = r.value.indexOfFirst { it.id == plugId }
                    if (i < 0) {
                        missing = true
                    } else {
                        missing = false
                        position = (i + 1) to r.value.size
                        // Nu suprascriem vederea primita ca raspuns la o comanda cu o sondare pornita
                        // inainte ca acea comanda sa se termine (ar aduce starea veche).
                        if (freshness.isFresh(ticket)) accept(r.value[i])
                    }
                }
                is CasaResult.Unauthorized -> {
                    lose(r)
                    return@WhileVisibleEffect
                }
                is CasaResult.Error -> Unit
            }
            delay(PlugsPollMs)
        }
    }

    /** Trimite o comanda; comenzile unei prize se succed una dupa alta (serverul limiteaza ~1/s). */
    fun command(
        group: String? = null,
        onDone: () -> Unit = {},
        onSuccess: () -> Unit = {},
        // Fara [onFailure], eroarea apare in subtitlul antetului; editorul de evenimente si-o arata singur.
        onFailure: ((String) -> Unit)? = null,
        call: (String) -> CasaResult<CasaPlug>
    ) {
        if (group != null) busy = busy + group
        freshness.commandStarted()
        scope.launch {
            val r = try {
                lock.withLock { withContext(Dispatchers.IO) { call(token) } }
            } finally {
                freshness.commandFinished()
            }
            if (group != null) busy = busy - group
            onDone()
            if (group == "power" || group == "events") {
                if (r is CasaResult.Ok) CasaHaptics.confirm(context) else if (r is CasaResult.Error) CasaHaptics.reject(context)
            }
            when (r) {
                is CasaResult.Ok -> {
                    accept(r.value)
                    onSuccess()
                }
                is CasaResult.Unauthorized -> lose(r)
                is CasaResult.Error -> if (onFailure != null) onFailure(r.message) else show(r.message)
            }
        }
    }

    fun sendInching(enabled: Boolean, minutes: Int) {
        command(
            group = "inching",
            // O comanda mai veche nu sterge o valoare mai noua, inca neexpediata.
            onDone = { if (pendingInching == minutes) pendingInching = null }
        ) { CasaRepository.setInching(it, plugId, enabled, minutes) }
    }

    // Starea de pornit/oprit a inchingului se citeste cand pleaca cererea, nu cand s-a apasat.
    val latestPlug by androidx.compose.runtime.rememberUpdatedState(plug)
    val debounce = remember {
        CasaInchingDebounce(scope, InchingDebounceMs) { minutes ->
            sendInching(latestPlug?.inchingEnabled ?: false, minutes)
        }
    }
    // La inchiderea ecranului (X / Back) valoarea in asteptare pleaca pe un scope care nu se opreste.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val m = debounce.pending
            debounce.cancel()
            if (m != null) {
                val enabled = latestPlug?.inchingEnabled ?: false
                CasaDetachedScope.launch {
                    val r = lock.withLock { CasaRepository.setInching(token, plugId, enabled, m) }
                    access.onResult(token, r)
                }
            }
        }
    }

    val p = plug
    CasaDetailFrame {
        if (p == null) {
            CasaDetailHeader(
                title = "PRIZA",
                subtitle = if (missing) "PRIZA NECUNOSCUTA" else if (stale) "ASTEPT DATE" else "SE INCARCA...",
                subtitleColor = if (missing) RetroRed else RetroYellow,
                onClose = onClose
            ) {}
            return@CasaDetailFrame
        }
        val on = pendingOn ?: p.on
        // Comenzile merg si cand priza pare offline (serverul incearca oricum); doar datele vechi le opresc.
        val usable = !stale
        val reading = p.online && !stale
        val defaultAction = casaCountdownDefaultAction(on)
        val elapsed = ((clock.value - recvMs) / 1000).coerceAtLeast(0)
        val inchingMinutes = pendingInching ?: casaInchingClamp(p.inchingMinutes)
        val sub = when {
            message != null -> message!!
            stale -> "FARA LEGATURA"
            else -> casaPlugSubtitle(position.first, position.second, p.metered, p.online)
        }
        val subColor = if (message != null || stale || !p.online) RetroRed else CasaGreenText
        // Mesajele de eroare si "FARA LEGATURA" inlocuiesc tot subtitlul; altfel driverul inchide randul.
        val driverTag = if (message != null || stale) null else casaDriverLabel(p.driver)

        CasaDetailHeader(retroLabel(p.name), sub, subColor, onClose, onRename = { renaming = true }, subtitleTag = driverTag) {
            PlugPowerRow(
                plug = p,
                on = on,
                usable = usable,
                reading = reading,
                busy = "power" in busy,
                deadlineSeconds = p.offDeadline?.let { it.secondsLeft - elapsed },
                onToggle = {
                    val target = !on
                    pendingOn = target
                    command("power", onDone = { pendingOn = null }) { CasaRepository.switch(it, plugId, target) }
                }
            )
        }
        PlugCountdownCard(
            plug = p,
            usable = usable && "countdown" !in busy,
            action = action ?: defaultAction,
            onAction = { action = it },
            remaining = p.countdown?.let { it.secondsLeft - elapsed },
            onMinutes = { m ->
                val a = action ?: defaultAction
                command("countdown") { CasaRepository.setCountdown(it, plugId, a, m) }
            },
            onMore = { showDuration = true },
            onCancel = { command("countdown") { CasaRepository.cancelCountdown(it, plugId) } }
        )
        PlugEventsCard(
            plug = p,
            nowEpoch = clock.value / 1000,
            usable = usable,
            pendingEnabled = pendingEnabled,
            onToggle = { e ->
                val target = !(pendingEnabled[e.id] ?: e.enabled)
                pendingEnabled = pendingEnabled + (e.id to target)
                command(onDone = { pendingEnabled = pendingEnabled - e.id }) {
                    CasaRepository.updateEvent(it, plugId, e.id, enabled = target)
                }
            },
            onEdit = { e ->
                editorError = null
                editor = CasaEventDraft(e.id, e.action, e.time, e.days)
            },
            onDelete = { deleteTarget = it },
            onAdd = { action ->
                editorError = null
                editor = CasaEventDraft(null, action, casaDefaultEventTime(clock.value / 1000), null)
            },
            modifier = Modifier.weight(1f)
        )
        PlugInchingCard(
            enabled = p.inchingEnabled,
            minutes = inchingMinutes,
            usable = usable && "inching" !in busy,
            onToggle = {
                // Valoarea in asteptare pleaca impreuna cu comutarea, intr-o singura cerere.
                val minutes = pendingInching ?: casaInchingClamp(p.inchingMinutes)
                pendingInching = minutes
                debounce.cancel()
                sendInching(!p.inchingEnabled, minutes)
            },
            onStep = { dir ->
                val next = casaInchingStep(inchingMinutes, dir)
                if (next != inchingMinutes) {
                    pendingInching = next
                    debounce.set(next)
                }
            }
        )
    }

    if (showDuration) {
        var hours by remember { mutableIntStateOf(1) }
        var minutes by remember { mutableIntStateOf(0) }
        CasaDialog("ALTA DURATA", onDismiss = { showDuration = false }) {
            CasaStepper("ORE", hours.toString(), { hours = (hours - 1).coerceAtLeast(0) }, {
                hours = (hours + 1).coerceAtMost(24)
                if (hours == 24) minutes = 0
            })
            CasaStepper("MINUTE", minutes.toString().padStart(2, '0'), {
                minutes = (minutes - 5).coerceAtLeast(0)
            }, {
                if (hours < 24) minutes = (minutes + 5).coerceAtMost(55)
            })
            val a = action ?: casaCountdownDefaultAction(pendingOn ?: plug?.on ?: false)
            CasaText(
                "${if (a == "on") "PORNESTE" else "OPRESTE"} DUPA ${casaCountdownTotal(hours, minutes)} MIN",
                11f, RetroMuted, Modifier.fillMaxWidth(), align = TextAlign.Center
            )
            CasaDialogButtons(
                okText = if (a == "on") "PORNESTE" else "OPRESTE",
                okEnabled = casaCountdownValid(hours, minutes),
                onCancel = { showDuration = false },
                onOk = {
                    showDuration = false
                    val total = casaCountdownTotal(hours, minutes)
                    command("countdown") { CasaRepository.setCountdown(it, plugId, a, total) }
                }
            )
        }
    }
    if (renaming && p != null) {
        CasaRenameDialog(current = p.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            command { CasaRepository.renamePlug(it, plugId, name) }
        }
    }
    editor?.let { draft ->
        // Fiecare deschidere creeaza un CasaEventDraft nou: raspunsul tarziu al unui salvari se leaga de
        // editorul care l-a trimis (identitate), nu de cel deschis intre timp.
        androidx.compose.runtime.key(draft) {
            CasaEventEditor(
                draft = draft,
                saving = "events" in busy,
                error = editorError,
                onTouched = { editorError = null },
                onDismiss = { editor = null },
                onSave = { time, eventAction, days ->
                    command(
                        group = "events",
                        // Editorul a fost inchis (sau inlocuit) cat timp cererea era in zbor: succesul nu inchide
                        // editorul nou, iar eroarea apare in antetul paginii ca la celelalte comenzi.
                        onSuccess = { if (editor === draft) editor = null },
                        onFailure = { msg -> if (editor === draft) editorError = msg else show(msg) }
                    ) {
                        if (draft.id == null) {
                            CasaRepository.addEvent(it, plugId, time, eventAction, days)
                        } else {
                            CasaRepository.updateEvent(it, plugId, draft.id, time, eventAction, days, daily = days == null)
                        }
                    }
                }
            )
        }
    }
    deleteTarget?.let { e ->
        CasaDialog("STERGI EVENIMENTUL?", onDismiss = { deleteTarget = null }) {
            CasaText(
                "${e.time} ${casaActionText(e.action)} ${casaDaysText(e.days)}", 15f, CasaOrange,
                Modifier.fillMaxWidth(), bold = true, align = TextAlign.Center
            )
            CasaDialogButtons(okText = "STERGE", onCancel = { deleteTarget = null }, onOk = {
                deleteTarget = null
                command("events") { CasaRepository.deleteEvent(it, plugId, e.id) }
            })
        }
    }
}

@Composable
private fun PlugPowerRow(
    plug: CasaPlug,
    on: Boolean,
    usable: Boolean,
    reading: Boolean,
    busy: Boolean,
    deadlineSeconds: Long?,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Un singur rand (v3.45): comutator + PORNIT | placa de wati | placa kWh + AZI.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CasaToggle(
                    checked = on,
                    description = "${retroLabel(plug.name)}: ${if (on) "opreste" else "porneste"}",
                    modifier = Modifier.padding(top = 3.dp).width(100.dp).height(40.dp),
                    enabled = usable && !busy,
                    onClick = onToggle
                )
                CasaText(
                    if (on) "PORNIT" else "OPRIT", 12f, if (on) RetroSage else RetroMuted,
                    bold = true, spacing = 2f, align = TextAlign.Center
                )
            }
            if (plug.metered) {
                val power = plug.power
                val watts = if (reading && power != null) power.roundToInt().toString() else "---"
                val color = when {
                    watts == "---" -> RetroSage.copy(alpha = 0.35f)
                    on && power != null && power > 0.5 -> CasaOrange
                    else -> RetroSage.copy(alpha = 0.55f)
                }
                CasaWattsPlate(watts, color, Modifier.weight(1f).height(46.dp))
                Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val kwh = plug.energyToday?.let { String.format(Locale.US, "%.1f", it) } ?: "---"
                    CasaKwhPlate(kwh, Modifier.fillMaxWidth().height(46.dp))
                    CasaText("AZI", 11f, RetroMuted, spacing = 1.5f, align = TextAlign.Center)
                }
            } else {
                Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.Center) {
                    CasaText("PRIZA FARA CONTOR", 11f, RetroMuted, bold = true, spacing = 1.5f)
                }
            }
        }
        if (on && deadlineSeconds != null) {
            CasaText(casaDeadlineText(deadlineSeconds), 10f, CasaBlue, Modifier.padding(start = 8.dp, bottom = 2.dp), spacing = 0.5f)
        }
    }
}

/** kWh de azi: cifre VFD pe placa `dial_blank`, ca la wati. */
@Composable
private fun CasaKwhPlate(value: String, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.semantics { contentDescription = "$value kWh azi" }) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.retro_dashboard_dial_blank),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds
        )
        RetroVfdDisplay(
            value = value,
            unit = "kWh",
            color = RetroSage,
            embedded = true,
            horizontalPadding = 3.dp,
            modifier = Modifier
                .padding(start = maxWidth * 0.11f, top = maxHeight * 0.16f)
                .width(maxWidth * 0.78f)
                .height(maxHeight * 0.70f),
            description = ""
        )
    }
}

@Composable
private fun PlugCountdownCard(
    plug: CasaPlug,
    usable: Boolean,
    action: String,
    onAction: (String) -> Unit,
    remaining: Long?,
    onMinutes: (Int) -> Unit,
    onMore: () -> Unit,
    onCancel: () -> Unit
) {
    RetroPanelSurface(
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CasaSectionTitle("COUNTDOWN", "", RetroMuted, 12f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("30M" to 30, "1H" to 60, "2H" to 120, "6H" to 360).forEach { (label, min) ->
                CasaKey(label, Modifier.weight(1f).height(40.dp), enabled = usable, size = 13f,
                    description = "Countdown $label", onClick = { onMinutes(min) })
            }
            CasaKey("ALTA DURATA", Modifier.weight(1.45f).height(40.dp), enabled = usable, size = 10f,
                onClick = onMore)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CasaKey("PORNESTE", Modifier.weight(1f).height(38.dp), selected = action == "on", enabled = usable,
                size = 13f, onClick = { onAction("on") })
            CasaKey("OPRESTE", Modifier.weight(1f).height(38.dp), selected = action == "off", enabled = usable,
                size = 13f, onClick = { onAction("off") })
        }
        val cd = plug.countdown
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0C0F07).copy(alpha = 0.75f))
                .border(1.dp, RetroBrassLight.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cd == null || remaining == null) {
                CasaText("NICIUN COUNTDOWN ACTIV", 11f, RetroMuted, Modifier.weight(1f), spacing = 1f)
            } else {
                val (h, m) = casaDurationParts(remaining)
                Column(Modifier.weight(1f)) {
                    CasaText("COUNTDOWN ACTIV", 8f, RetroMuted, spacing = 1.5f)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        CasaText(if (cd.action == "on") "PORNIRE IN" else "OPRIRE IN", 11f, CasaBlue, bold = true, spacing = 0.8f)
                        if (h > 0) {
                            RetroVfdDisplay(h.toString(), "", CasaBlue, Modifier.width(24.dp).height(26.dp), embedded = true,
                                horizontalPadding = 0.dp, description = "")
                            CasaText("H", 10f, CasaBlue, bold = true)
                        }
                        RetroVfdDisplay(m.toString().padStart(2, '0'), "", CasaBlue, Modifier.width(34.dp).height(26.dp),
                            embedded = true, horizontalPadding = 0.dp, description = "")
                        CasaText("MIN", 10f, CasaBlue, bold = true)
                    }
                }
                CasaKey("ANULEAZA", Modifier.width(104.dp).height(34.dp), accent = RetroRed,
                    fill = CasaRedFillTop to CasaRedFillBottom, enabled = usable, size = 11f, onClick = onCancel)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Evenimente (PORNESTE / OPRESTE la o ora, in anumite zile)
// ---------------------------------------------------------------------------------------------

/** Ce se editeaza in dialog: [id] null = eveniment nou; [days] null = zilnic. */
private data class CasaEventDraft(val id: String?, val action: String, val time: String, val days: List<Int>?)

private val CasaChipOnFill = Color(0xFF1E3410)
private val CasaChipOnEdge = Color(0xFF4F8A27)
private val CasaChipOnText = Color(0xFFB8F27A)
private val CasaChipOffFill = Color(0xFF3E120C)
private val CasaChipOffEdge = Color(0xFF8A3326)
private val CasaChipOffText = Color(0xFFFFA89A)

@Composable
private fun PlugEventsCard(
    plug: CasaPlug,
    nowEpoch: Long,
    usable: Boolean,
    pendingEnabled: Map<String, Boolean>,
    onToggle: (CasaEvent) -> Unit,
    onEdit: (CasaEvent) -> Unit,
    onDelete: (CasaEvent) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    RetroPanelSurface(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        fillContent = true,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        CasaSectionTitle("PROGRAMARI EVENIMENTE", "", RetroMuted, 12f)
        CasaNextEventLine(plug.nextEvent, nowEpoch)
        // Lista deruleaza in interior cand nu incap toate randurile (pagina in sine nu deruleaza niciodata).
        val scroll = rememberScrollState()
        val events = plug.events.sortedWith(compareBy({ it.time }, { it.action }))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    if (scroll.maxValue > 0) {
                        val total = size.height + scroll.maxValue
                        val barH = (size.height * size.height / total).coerceAtLeast(14.dp.toPx())
                        val y = (size.height - barH) * scroll.value / scroll.maxValue
                        drawRoundRect(
                            RetroBrassLight.copy(alpha = 0.75f),
                            topLeft = Offset(size.width - 4.dp.toPx(), y),
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), barH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                        )
                    }
                }
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (events.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                        CasaText("ADAUGA O PORNIRE SAU O OPRIRE", 10f, RetroMuted, spacing = 1f)
                    }
                }
                events.forEachIndexed { i, e ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(RetroBrassLight.copy(alpha = 0.25f)))
                    val enabled = pendingEnabled[e.id] ?: e.enabled
                    CasaEventRow(e, enabled, usable, onToggle = { onToggle(e) }, onEdit = { onEdit(e) }, onDelete = { onDelete(e) })
                }
            }
        }
        if (casaCanAddEvent(plug.events.size)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CasaKey("+ PORNIRE", Modifier.weight(1f).height(34.dp), enabled = usable, size = 12f,
                    description = "Adauga pornire", onClick = { onAdd("on") })
                CasaKey("+ OPRIRE", Modifier.weight(1f).height(34.dp), enabled = usable, size = 12f,
                    description = "Adauga oprire", onClick = { onAdd("off") })
            }
        } else {
            Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                CasaText("MAXIM $MaxEvents EVENIMENTE", 10f, RetroMuted, spacing = 1f)
            }
        }
    }
}

/** "URMATORUL: OPRESTE 16:00 · IN 2H 14M" sau "NICIUN EVENIMENT" cand nimic nu e programat. */
@Composable
private fun CasaNextEventLine(next: CasaNextEvent?, nowEpoch: Long) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(shape)
            .background(Color(0xFF0C0F07).copy(alpha = 0.75f))
            .border(1.dp, RetroBrassLight.copy(alpha = 0.30f), shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (next == null) {
            CasaText("NICIUN EVENIMENT", 11f, RetroMuted, spacing = 1f)
        } else {
            val text = casaNextEventText(next, nowEpoch, BucharestZone)
            val head = text.substringBefore(" · ")
            val tail = text.substringAfter(" · ", "")
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = RetroMuted)) { append("URMATORUL: ") }
                    withStyle(SpanStyle(color = CasaBlue, fontWeight = FontWeight.Bold)) { append(head) }
                    if (tail.isNotEmpty()) withStyle(SpanStyle(color = RetroMuted)) { append(" · $tail") }
                },
                fontFamily = RetroMono,
                fontSize = fixedSp(11f),
                letterSpacing = fixedSp(0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CasaActionChip(action: String) {
    val on = action == "on"
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .width(82.dp)
            .clip(shape)
            .background(if (on) CasaChipOnFill else CasaChipOffFill)
            .border(1.5.dp, if (on) CasaChipOnEdge else CasaChipOffEdge, shape)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        CasaText(casaActionText(action), 10f, if (on) CasaChipOnText else CasaChipOffText, bold = true, spacing = 0.8f)
    }
}

@Composable
private fun CasaEventRow(
    e: CasaEvent,
    enabled: Boolean,
    usable: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val what = "${e.time} ${casaActionText(e.action)} ${casaDaysText(e.days)}"
    Row(
        Modifier.fillMaxWidth().height(CasaEventRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Partea din stanga (LED, ora, eticheta, zilele) deschide editorul; comutatorul si X sunt separate.
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .alpha(if (enabled) 1f else 0.5f)
                .semantics { contentDescription = "Editeaza evenimentul $what" }
                .clickable(enabled = usable, onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RetroLed(color = RetroSage, active = enabled, modifier = Modifier.size(18.dp))
            CasaText(e.time, 19f, CasaOrange, bold = true, spacing = 1f)
            CasaActionChip(e.action)
            CasaText(casaDaysText(e.days), 9f, RetroMuted, Modifier.weight(1f), maxLines = 2, spacing = 0.3f)
        }
        // Zona de atingere a comutatorului (60 x 40 dp) e mai mare decat pista lui vizibila (60 x ~20 dp).
        CasaToggle(
            checked = enabled,
            description = "Eveniment $what: ${if (enabled) "dezactiveaza" else "activeaza"}",
            modifier = Modifier.width(60.dp).height(CasaEventRowHeight),
            enabled = usable,
            onClick = onToggle
        )
        // X-ul se vede de 32 dp, dar se atinge pe 40 x 40 dp.
        Box(
            Modifier
                .size(40.dp)
                .semantics { contentDescription = "Sterge evenimentul $what" }
                .clickable(enabled = usable, onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(32.dp)) {
                drawCircle(RetroBrassLight.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
                val a = size.minDimension * 0.22f
                val w = 1.8.dp.toPx()
                drawLine(RetroMuted, Offset(center.x - a, center.y - a), Offset(center.x + a, center.y + a), w, StrokeCap.Round)
                drawLine(RetroMuted, Offset(center.x - a, center.y + a), Offset(center.x + a, center.y - a), w, StrokeCap.Round)
            }
        }
    }
}

/** Inaltimea unui rand de eveniment (v3.45: 40 dp, ca sa incapa 5 randuri pe Note 9 fara derulare). */
private val CasaEventRowHeight = 40.dp

private const val CasaRepeatStartMs = 400L

/**
 * Buton +/- cu repetare: o apasare scurta = un pas, apasarea lunga repeta (mai repede dupa cateva pasi).
 * Atingerea se citeste in trecerea Initial, iar ridicarea degetului se consuma, deci `onClick` al butonului
 * metalic NU se declanseaza si pentru atingere (un singur pas, nu doi). `onClick` ramane pentru actiunea de
 * accesibilitate (TalkBack "activeaza"), care nu trece prin gest: face un pas.
 */
@Composable
private fun CasaRepeatKey(text: String, description: String, modifier: Modifier, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    RetroMetalButton(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                step()
                val repeat = scope.launch {
                    delay(CasaRepeatStartMs)
                    var n = 0
                    while (true) {
                        step()
                        n++
                        delay(if (n < 12) 90L else 45L)
                    }
                }
                try {
                    waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                } finally {
                    repeat.cancel()
                }
            }
        },
        selected = false,
        description = description,
        onClick = { step() }
    ) {
        CasaText(text, 20f, RetroText, Modifier.align(Alignment.Center), bold = true, align = TextAlign.Center)
    }
}

@Composable
private fun CasaEditorLabel(text: String) {
    CasaText(text, 10f, RetroMuted, Modifier.fillMaxWidth(), spacing = 2f)
}

/** Editorul unui eveniment nou sau existent; eroarea serverului (ex. conflict) apare in dialog. */
@Composable
private fun CasaEventEditor(
    draft: CasaEventDraft,
    saving: Boolean,
    error: String?,
    onTouched: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (time: String, action: String, days: List<Int>?) -> Unit
) {
    var action by remember { mutableStateOf(draft.action) }
    var time by remember { mutableStateOf(draft.time) }
    var daily by remember { mutableStateOf(draft.days == null) }
    var picked by remember { mutableStateOf(draft.days?.toSet() ?: emptySet()) }
    val valid = casaEventDaysValid(daily, picked)

    CasaDialog(if (draft.id == null) "EVENIMENT NOU" else "EDITEAZA EVENIMENTUL", onDismiss = onDismiss) {
        CasaEditorLabel("CE FACE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CasaKey("PORNESTE", Modifier.weight(1f).height(44.dp), selected = action == "on", size = 13f,
                onClick = { action = "on"; onTouched() })
            CasaKey("OPRESTE", Modifier.weight(1f).height(44.dp), selected = action == "off", size = 13f,
                onClick = { action = "off"; onTouched() })
        }

        CasaEditorLabel("LA ORA")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CasaRepeatKey("+", "Ore mai multe", Modifier.width(52.dp).height(40.dp)) { time = casaStepTime(time, 1, 0); onTouched() }
                CasaRepeatKey("−", "Ore mai putine", Modifier.width(52.dp).height(40.dp)) { time = casaStepTime(time, -1, 0); onTouched() }
            }
            RetroInsetSurface(Modifier.weight(1f).height(86.dp)) {
                CasaText(time, 44f, RetroSage, Modifier.align(Alignment.Center), bold = true, spacing = 2f, align = TextAlign.Center)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CasaRepeatKey("+", "Minute mai multe", Modifier.width(52.dp).height(40.dp)) { time = casaStepTime(time, 0, 1); onTouched() }
                CasaRepeatKey("−", "Minute mai putine", Modifier.width(52.dp).height(40.dp)) { time = casaStepTime(time, 0, -1); onTouched() }
            }
        }
        CasaText("ORE · MINUTE (APASARE LUNGA = RAPID)", 9f, RetroMuted, Modifier.fillMaxWidth(), spacing = 0.5f, align = TextAlign.Center)

        CasaEditorLabel("IN CE ZILE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CasaKey("ZILNIC", Modifier.weight(1f).height(40.dp), selected = daily, size = 12f,
                onClick = { daily = true; onTouched() })
            CasaKey("ALEG ZILELE", Modifier.weight(1f).height(40.dp), selected = !daily, size = 12f,
                onClick = { daily = false; onTouched() })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CasaDayShort.forEachIndexed { i, label ->
                val day = i + 1
                val chosen = !daily && day in picked
                CasaKey(
                    label,
                    // La ZILNIC zilele sunt estompate; o atingere trece pe ALEG ZILELE cu acea zi.
                    Modifier.weight(1f).height(40.dp).alpha(if (daily) 0.45f else 1f),
                    selected = chosen,
                    size = 11f,
                    description = "Ziua $label: ${if (chosen) "aleasa" else "neselectata"}",
                    onClick = {
                        if (daily) {
                            daily = false
                            picked = setOf(day)
                        } else {
                            picked = if (day in picked) picked - day else picked + day
                        }
                        onTouched()
                    }
                )
            }
        }

        if (error != null) {
            CasaText(error, 10f, RetroRed, Modifier.fillMaxWidth(), bold = true, spacing = 0.5f, align = TextAlign.Center, maxLines = 3)
        }
        CasaDialogButtons(
            okText = if (saving) "SE SALVEAZA" else "SALVEAZA",
            okEnabled = valid && !saving,
            cancelText = "ANULEAZA",
            onCancel = onDismiss,
            onOk = { onSave(time, action, casaEventDays(daily, picked)) }
        )
    }
}

@Composable
private fun PlugInchingCard(
    enabled: Boolean,
    minutes: Int,
    usable: Boolean,
    onToggle: () -> Unit,
    onStep: (Int) -> Unit
) {
    RetroPanelSurface(
        // Marginile laterale de 18 dp tin textul si butonul + departe de suruburile din colturile placii.
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
    ) {
        // Un singur rand (v3.45), fara linie de titlu: INCHING + OPRIRE AUTOMATA | comutator | - | durata | +.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "INCHING",
                    color = RetroText,
                    fontFamily = RetroMono,
                    fontSize = fixedSp(12f),
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = fixedSp(2f),
                    maxLines = 1
                )
                CasaText("OPRIRE AUTOMATA", 8.5f, RetroMuted, spacing = 0.5f, maxLines = 2)
            }
            CasaToggle(
                checked = enabled,
                // Explicatia scoasa din ecran (v3.45) ramane in descrierea de accesibilitate.
                description = "Inching: ${if (enabled) "dezactiveaza" else "activeaza"}. " +
                    "Dupa fiecare pornire, priza se stinge singura dupa durata aleasa.",
                modifier = Modifier.width(60.dp).height(36.dp),
                enabled = usable,
                onClick = onToggle
            )
            CasaKey("−", Modifier.width(44.dp).height(36.dp), enabled = usable, size = 18f,
                description = "Inching mai putin", onClick = { onStep(-1) })
            RetroInsetSurface(Modifier.width(84.dp).height(36.dp)) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RetroVfdDisplay(minutes.toString(), "", RetroSage, Modifier.width(42.dp).height(28.dp), embedded = true,
                        horizontalPadding = 0.dp, description = "$minutes minute")
                    CasaText("MIN", 10f, RetroSage, bold = true)
                }
            }
            CasaKey("+", Modifier.width(44.dp).height(36.dp), enabled = usable, size = 18f,
                description = "Inching mai mult", onClick = { onStep(1) })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// E: detaliul senzorului
// ---------------------------------------------------------------------------------------------

private sealed interface HistoryState {
    object Loading : HistoryState
    object Failed : HistoryState
    class Ready(val data: CasaSensorHistory, val nowSec: Long) : HistoryState
}

private val Ranges = listOf("2h" to "2H", "1d" to "1D", "7d" to "7D")

@Composable
internal fun RetroSensorDetail(sensorId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val access = remember { casaAccess(context) }
    val token = remember { access.token }
    if (token == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
        return
    }
    BackHandler(onBack = onClose)
    val clock = rememberCasaClock()
    var sensors by remember { mutableStateOf<CasaSensors?>(null) }
    var range by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("1d") }
    var history by remember { mutableStateOf<HistoryState>(HistoryState.Loading) }
    var renaming by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun lose(r: CasaResult<*>) {
        access.onResult(token, r)
        onClose()
    }

    // Valorile curente: ca pe pagina CASA, o data pe minut.
    WhileVisibleEffect(token) {
        while (true) {
            val r = withContext(Dispatchers.IO) { CasaRepository.sensors(token) }
            when (r) {
                is CasaResult.Ok -> sensors = r.value
                is CasaResult.Unauthorized -> {
                    lose(r)
                    return@WhileVisibleEffect
                }
                is CasaResult.Error -> Unit
            }
            delay(60_000)
        }
    }
    // Istoricul: o singura cerere la deschidere si la fiecare schimbare de perioada.
    WhileVisibleEffect(token, range) {
        history = HistoryState.Loading
        val r = withContext(Dispatchers.IO) { CasaRepository.sensorHistory(token, sensorId, range) }
        when (r) {
            is CasaResult.Ok -> history = HistoryState.Ready(r.value, System.currentTimeMillis() / 1000)
            is CasaResult.Unauthorized -> lose(r)
            is CasaResult.Error -> history = HistoryState.Failed
        }
    }

    val s = sensors?.sensors?.firstOrNull { it.id == sensorId }
    val cloudOk = sensors?.cloudOk == true
    val nowSec = clock.value / 1000
    val stale = s == null || isStale(nowSec, s.updated)
    CasaDetailFrame {
        val sub = when {
            renameError != null -> renameError!!
            sensors == null -> "SE INCARCA..."
            s == null -> "SENZOR NECUNOSCUT"
            !cloudOk -> "CLOUD DECONECTAT · ${formatAge(nowSec, s.updated)}"
            else -> "ACTUALIZAT ${formatAge(nowSec, s.updated)} · ${casaBatteryText(s.battery)}"
        }
        CasaDetailHeader(
            title = s?.let { casaSensorTitle(it.name) } ?: "SENZOR",
            subtitle = sub,
            subtitleColor = if (renameError != null || (sensors != null && (s == null || !cloudOk || stale))) RetroRed else CasaGreenText,
            onClose = onClose,
            onRename = if (s != null) ({ renaming = true }) else null
        ) {
            Row(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensorValuePlate(
                    label = "TEMPERATURA",
                    value = casaTemperatureText(s?.temperature),
                    unit = "°C",
                    color = CasaOrange,
                    dim = stale,
                    isTemp = true,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                SensorValuePlate(
                    label = "UMIDITATE",
                    value = s?.humidity?.roundToInt()?.toString() ?: "—",
                    unit = "%",
                    color = CasaBlue,
                    dim = stale,
                    isTemp = false,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CasaText("PERIOADA", 11f, RetroMuted, Modifier.padding(start = 8.dp).width(86.dp), spacing = 1.2f)
            Ranges.forEach { (key, label) ->
                CasaKey(label, Modifier.weight(1f).fillMaxHeight(), selected = key == range, size = 14f,
                    description = "Perioada $label", onClick = { range = key })
            }
        }
        SensorChartCard(range, history, Modifier.weight(1f))
        SensorStatsCard(range, (history as? HistoryState.Ready)?.data)
    }
    val current = s
    if (renaming && current != null) {
        CasaRenameDialog(current = current.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { CasaRepository.renameSensor(token, sensorId, name) }) {
                    is CasaResult.Ok -> {
                        renameError = null
                        sensors = sensors?.let { all ->
                            all.copy(sensors = all.sensors.map { if (it.id == sensorId) it.copy(name = r.value) else it })
                        }
                    }
                    is CasaResult.Unauthorized -> lose(r)
                    is CasaResult.Error -> {
                        renameError = r.message
                        delay(ErrorShownMs)
                        renameError = null
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorValuePlate(
    label: String,
    value: String,
    unit: String,
    color: Color,
    dim: Boolean,
    isTemp: Boolean,
    modifier: Modifier
) {
    CasaPlateFrame(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.height(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(9.dp)) {
                    if (isTemp) drawThermometer(color) else drawDrop(color)
                }
                CasaText(label, 9f, color.copy(alpha = 0.9f), spacing = 1f)
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                RetroVfdDisplay(
                    value = value,
                    unit = "",
                    color = if (dim) color.copy(alpha = 0.45f) else color,
                    embedded = true,
                    horizontalPadding = 0.dp,
                    modifier = Modifier.width(78.dp).fillMaxHeight(),
                    description = "$label $value $unit"
                )
                CasaText(unit, 13f, color.copy(alpha = 0.85f), Modifier.padding(start = 2.dp, bottom = 4.dp), bold = true)
            }
        }
    }
}

private fun DrawScope.drawThermometer(color: Color) {
    val s = size.minDimension
    val w = s * 0.16f
    drawLine(color, Offset(s * 0.5f, s * 0.08f), Offset(s * 0.5f, s * 0.68f), w, StrokeCap.Round)
    drawCircle(color, s * 0.24f, Offset(s * 0.5f, s * 0.78f))
}

@Composable
private fun SensorChartCard(range: String, state: HistoryState, modifier: Modifier) {
    RetroInsetSurface(modifier.fillMaxWidth()) {
        when (state) {
            is HistoryState.Ready -> {
                val d = state.data
                if (d.temperature.size + d.humidity.size < 2) {
                    CasaText("ISTORIC INDISPONIBIL", 12f, RetroYellow, Modifier.align(Alignment.Center), bold = true, spacing = 1.5f)
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CasaText("— TEMP °C", 9f, CasaOrange, Modifier.weight(1f), spacing = 0.8f)
                            CasaText(casaRangeTitle(range), 9f, RetroText, spacing = 1f, align = TextAlign.Center)
                            CasaText("— UMID %", 9f, CasaBlue, Modifier.weight(1f), spacing = 0.8f, align = TextAlign.End)
                        }
                        SensorChart(range, d, state.nowSec, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            HistoryState.Loading -> CasaText("SE INCARCA...", 12f, RetroMuted, Modifier.align(Alignment.Center), bold = true, spacing = 1.5f)
            HistoryState.Failed -> CasaText("ISTORIC INDISPONIBIL", 12f, RetroYellow, Modifier.align(Alignment.Center), bold = true, spacing = 1.5f)
        }
    }
}

@Composable
private fun SensorChart(range: String, d: CasaSensorHistory, nowSec: Long, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontFamily = RetroMono, fontWeight = FontWeight.Normal)
    Canvas(modifier) {
        val t1 = nowSec
        val t0 = nowSec - casaRangeSeconds(range)
        val leftW = 34.dp.toPx()
        val rightW = 40.dp.toPx()
        val bottomH = 16.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotL = leftW
        val plotR = size.width - rightW
        val plotT = topPad
        val plotB = size.height - bottomH
        val w = plotR - plotL
        val h = plotB - plotT
        val fs = 9.dp.toSp()

        val tAxis = if (d.temperature.isNotEmpty()) {
            val lo = minOf(d.temperature.minOf { it.second }, d.tempMin?.first ?: Double.MAX_VALUE)
            val hi = maxOf(d.temperature.maxOf { it.second }, d.tempMax?.first ?: -Double.MAX_VALUE)
            casaNiceAxis(lo, hi, 4, 1.0)
        } else null
        val hAxis = if (d.humidity.isNotEmpty()) {
            val lo = minOf(d.humidity.minOf { it.second }, d.humMin?.first ?: Double.MAX_VALUE)
            val hi = maxOf(d.humidity.maxOf { it.second }, d.humMax?.first ?: -Double.MAX_VALUE)
            casaNiceAxis(lo, hi, 4, 5.0, cap = 100.0)
        } else null

        // grila
        for (i in 0..4) {
            val y = plotB - h * i / 4f
            drawLine(Color(0xFF9BC3CF).copy(alpha = 0.10f), Offset(plotL, y), Offset(plotR, y), 1.dp.toPx())
            tAxis?.let {
                val txt = casaAxisLabel(it.lo + it.step * i, it.step, "°")
                drawAxisText(measurer, txt, label, fs, CasaOrange.copy(alpha = 0.85f), Offset(plotL - 4.dp.toPx(), y), alignRight = true)
            }
            hAxis?.let {
                val txt = casaAxisLabel(it.lo + it.step * i, it.step, "%")
                drawAxisText(measurer, txt, label, fs, CasaBlue.copy(alpha = 0.85f), Offset(plotR + 4.dp.toPx(), y), alignRight = false)
            }
        }
        for (tick in casaTimeTicks(range, nowSec)) {
            val x = plotL + w * casaChartFraction(tick.t, t0, t1)
            drawLine(Color(0xFF9BC3CF).copy(alpha = 0.10f), Offset(x, plotT), Offset(x, plotB), 1.dp.toPx())
            val m = measurer.measure(tick.label, label.copy(color = RetroMuted, fontSize = fs))
            val tx = (x - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            drawText(m, topLeft = Offset(tx, plotB + 3.dp.toPx()))
        }

        fun px(t: Long) = plotL + w * casaChartFraction(t, t0, t1)
        fun series(points: List<Pair<Long, Double>>, axis: CasaAxis, color: Color, core: Float) {
            if (points.isEmpty()) return
            val path = Path()
            points.forEachIndexed { i, (t, v) ->
                val x = px(t)
                val y = plotB - h * ((v - axis.lo) / (axis.hi - axis.lo)).toFloat().coerceIn(0f, 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color.copy(alpha = 0.25f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color, style = Stroke(core.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun marker(t: Long, v: Double, axis: CasaAxis, color: Color) {
            val x = px(t)
            val y = plotB - h * ((v - axis.lo) / (axis.hi - axis.lo)).toFloat().coerceIn(0f, 1f)
            drawCircle(Color(0xFF0C0F07), 5.dp.toPx(), Offset(x, y))
            drawCircle(color, 5.dp.toPx(), Offset(x, y), style = Stroke(1.6.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.85f), 1.6.dp.toPx(), Offset(x, y))
        }
        hAxis?.let { series(d.humidity, it, CasaBlue, 1.5f) }
        tAxis?.let { series(d.temperature, it, CasaOrange, 2f) }
        if (hAxis != null) {
            d.humMin?.let { marker(it.second, it.first, hAxis, CasaBlue) }
            d.humMax?.let { marker(it.second, it.first, hAxis, CasaBlue) }
        }
        if (tAxis != null) {
            d.tempMin?.let { marker(it.second, it.first, tAxis, CasaOrange) }
            d.tempMax?.let { marker(it.second, it.first, tAxis, CasaOrange) }
            d.temperature.lastOrNull()?.let { (t, v) ->
                val y = plotB - h * ((v - tAxis.lo) / (tAxis.hi - tAxis.lo)).toFloat().coerceIn(0f, 1f)
                drawCircle(CasaOrange, 4.dp.toPx(), Offset(px(t), y))
            }
        }
    }
}

private fun DrawScope.drawAxisText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    anchor: Offset,
    alignRight: Boolean
) {
    val m = measurer.measure(text, style.copy(color = color, fontSize = fontSize))
    val x = if (alignRight) anchor.x - m.size.width else anchor.x
    drawText(m, topLeft = Offset(x, anchor.y - m.size.height / 2f))
}

@Composable
private fun SensorStatsCard(range: String, d: CasaSensorHistory?) {
    RetroPanelSurface(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            StatBlock("TEMPERATURA", CasaOrange, d?.tempMin?.let { statValue(it.first, "°") },
                d?.tempMax?.let { statValue(it.first, "°") }, d?.tempMin?.let { casaStatTime(range, it.second) },
                d?.tempMax?.let { casaStatTime(range, it.second) }, Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(52.dp).background(RetroBrassLight.copy(alpha = 0.3f)))
            StatBlock("UMIDITATE", CasaBlue, d?.humMin?.let { "${it.first.roundToInt()}%" },
                d?.humMax?.let { "${it.first.roundToInt()}%" }, d?.humMin?.let { casaStatTime(range, it.second) },
                d?.humMax?.let { casaStatTime(range, it.second) }, Modifier.weight(1f))
        }
    }
}

private fun statValue(v: Double, unit: String) = String.format(Locale.US, "%.1f", v) + unit

@Composable
private fun StatBlock(
    title: String,
    color: Color,
    min: String?,
    max: String?,
    minAt: String?,
    maxAt: String?,
    modifier: Modifier
) {
    Column(modifier) {
        CasaText(title, 9f, RetroMuted, spacing = 1f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CasaText("MIN", 11f, RetroText, bold = true)
                    CasaText(min ?: "—", 11f, color, bold = true)
                }
                CasaText(minAt ?: "", 9f, RetroMuted)
            }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CasaText("MAX", 11f, RetroText, bold = true)
                    CasaText(max ?: "—", 11f, color, bold = true)
                }
                CasaText(maxAt ?: "", 9f, RetroMuted)
            }
        }
    }
}

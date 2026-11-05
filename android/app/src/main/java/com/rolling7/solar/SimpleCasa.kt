package com.rolling7.solar

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val SensorsPollMs = 60_000L

// ---------------------------------------------------------------------------------------------
// Jetonul CASA ca stare Compose (il citesc cardul Simple si randurile din SETARI)
// ---------------------------------------------------------------------------------------------

/**
 * Jetonul curent al telefonului, urmarit in fisierul `casa_access`: se actualizeaza singur la
 * inregistrare, la "UITA TELEFONUL" si cand un 401 il sterge, indiferent din ce ecran vine schimbarea.
 */
@Composable
internal fun rememberCasaToken(): State<String?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(casaAccess(context).token) }
    DisposableEffect(context) {
        val prefs = context.applicationContext.getSharedPreferences(CasaAccess.PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> state.value = CasaAccess(p).token }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        state.value = CasaAccess(prefs).token
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Confirmarea pentru "UITA TELEFONUL": sterge doar jetonul local; serverul tine telefonul pe lista pana e revocat acolo. */
@Composable
internal fun CasaForgetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uiti telefonul?") },
        text = {
            Text(
                "Aplicatia sterge accesul la CASA de pe acest telefon si cere din nou parola. " +
                    "Telefonul ramane pe lista serverului pana este revocat acolo."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("UITA TELEFONUL") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("RENUNT") } }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Detaliul unei prize sau al unui senzor pe tema Simple: aceleasi ecrane Retro, pe tot ecranul, intr-un dialog.
 * Cat timp e deschis, cardul din spate nu mai interogheaza serverul.
 */
@Composable
internal fun SimpleCasaDetailDialog(plugId: String?, sensorId: String?, onClose: () -> Unit) {
    if (plugId == null && sensorId == null) return
    // Barele de sistem apartin ferestrei aplicatiei, nu dialogului: cat e deschis detaliul le facem de culoarea
    // fundalului Retro (altfel se vede pagina Simple, deschisa la culoare, prin ele) si le readucem la inchidere.
    val activityWindow = LocalContext.current.findActivity()?.window
    val activityView = LocalView.current
    DisposableEffect(activityWindow) {
        if (activityWindow == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activityWindow, activityView)
        val oldStatus = activityWindow.statusBarColor
        val oldNav = activityWindow.navigationBarColor
        val oldLightStatus = controller.isAppearanceLightStatusBars
        val oldLightNav = controller.isAppearanceLightNavigationBars
        activityWindow.statusBarColor = RetroBackground.toArgb()
        activityWindow.navigationBarColor = RetroBackground.toArgb()
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            activityWindow.statusBarColor = oldStatus
            activityWindow.navigationBarColor = oldNav
            controller.isAppearanceLightStatusBars = oldLightStatus
            controller.isAppearanceLightNavigationBars = oldLightNav
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setDimAmount(0f)
        }
        Box(Modifier.fillMaxSize()) {
            RetroIndustrialBackdrop(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                if (plugId != null) RetroPlugDetail(plugId, onClose) else RetroSensorDetail(sensorId!!, onClose)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cardul CASA de pe SISTEM (tema Simple)
// ---------------------------------------------------------------------------------------------

private fun simpleAgeText(nowSec: Long, updated: Long?): String =
    formatAge(nowSec, updated).lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }

private fun simpleTemperatureColor(t: Double?): Color = when {
    t == null -> CMuted
    t < 10.0 -> CHouse
    t > 28.0 -> CBat
    else -> CPv
}

@Composable
private fun SimpleCasaSurface(status: String?, statusColor: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CPanel,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Casa", color = CText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (status != null) Text(status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Prizele (nume, W, comutator) si senzorii (temperatura, umiditate, vechime) de pe SISTEM. Aceleasi reguli
 * ca pe pagina Retro: sondare prin `WhileVisibleEffect`, valori devenite liniute peste 20 s fara raspuns, 401 = jetonul
 * se sterge si cardul cere parola o singura data. [paused] opreste sondarea cat e deschis un detaliu.
 */
@Composable
internal fun SimpleCasaCard(
    onOpenPlug: (String) -> Unit,
    onOpenSensor: (String) -> Unit,
    paused: Boolean = false
) {
    val context = LocalContext.current
    val access = remember { casaAccess(context) }
    val token by rememberCasaToken()
    val t = token
    if (t == null) {
        SimpleCasaCardRegister(access)
    } else {
        SimpleCasaCardContent(access, t, paused, onOpenPlug, onOpenSensor)
    }
}

@Composable
private fun SimpleCasaCardRegister(access: CasaAccess) {
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
                    password = ""
                    access.save(r.value)
                }
                is CasaResult.Error -> error = r.message
                is CasaResult.Unauthorized -> error = "PAROLA GRESITA"
            }
        }
    }

    SimpleCasaSurface(status = "Neinregistrat", statusColor = CMuted) {
        Text(
            "Telefonul nu este inregistrat. Introdu parola pentru a comanda prizele si a vedea senzorii.",
            color = CMuted, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            singleLine = true,
            readOnly = busy,
            label = { Text("Parola") },
            isError = error != null,
            supportingText = { Text(error ?: if (busy) "Se inregistreaza..." else " ") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Parola CASA" }
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Inregistreaza")
        }
    }
}

@Composable
private fun SimpleCasaCardContent(
    access: CasaAccess,
    token: String,
    paused: Boolean,
    onOpenPlug: (String) -> Unit,
    onOpenSensor: (String) -> Unit
) {
    var plugs by remember { mutableStateOf<List<CasaPlug>>(emptyList()) }
    var sensors by remember { mutableStateOf<CasaSensors?>(null) }
    var lastGoodPlugsMs by remember { mutableLongStateOf(0L) }
    // Starea dorita de utilizator pana raspunde priza: id -> pornit?
    var optimistic by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var switchError by remember { mutableStateOf<String?>(null) }
    var errorStamp by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val clock = rememberCasaClock()
    val plugsStale by remember { derivedStateOf { clock.value - lastGoodPlugsMs > PlugsStaleMs } }

    // La inchiderea detaliului sondarea reporneste: ceasul de dinainte ar arata o clipa "Astept date".
    LaunchedEffect(paused) {
        if (!paused && lastGoodPlugsMs != 0L) lastGoodPlugsMs = System.currentTimeMillis()
    }
    // Un 401 sterge jetonul (daca el era cel respins); `rememberCasaToken` muta cardul pe parola.
    WhileVisibleEffect(token, paused) {
        if (paused) return@WhileVisibleEffect
        while (true) {
            val r = withContext(Dispatchers.IO) { CasaRepository.plugs(token) }
            when (r) {
                is CasaResult.Ok -> {
                    plugs = r.value
                    lastGoodPlugsMs = System.currentTimeMillis()
                }
                is CasaResult.Unauthorized -> {
                    access.onResult(token, r)
                    return@WhileVisibleEffect
                }
                is CasaResult.Error -> Unit
            }
            delay(PlugsPollMs)
        }
    }
    WhileVisibleEffect(token, paused) {
        if (paused) return@WhileVisibleEffect
        while (true) {
            val r = withContext(Dispatchers.IO) { CasaRepository.sensors(token) }
            when (r) {
                is CasaResult.Ok -> sensors = r.value
                is CasaResult.Unauthorized -> {
                    access.onResult(token, r)
                    return@WhileVisibleEffect
                }
                is CasaResult.Error -> Unit
            }
            delay(SensorsPollMs)
        }
    }

    fun toggle(p: CasaPlug) {
        if (p.id in optimistic) return
        val target = !(optimistic[p.id] ?: p.on)
        optimistic = optimistic + (p.id to target)
        scope.launch {
            val r = withContext(Dispatchers.IO) { CasaRepository.switch(token, p.id, target) }
            optimistic = optimistic - p.id
            when (r) {
                is CasaResult.Ok -> {
                    plugs = plugs.map { if (it.id == r.value.id) r.value else it }
                    lastGoodPlugsMs = System.currentTimeMillis()
                }
                is CasaResult.Unauthorized -> access.onResult(token, r)
                is CasaResult.Error -> {
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

    val (count, watts) = casaPlugsSummary(plugs) { optimistic[it.id] ?: it.on }
    val (status, statusColor) = when {
        switchError != null -> switchError!! to CGrid
        plugs.isEmpty() || plugsStale -> "Astept date" to CBat
        else -> "$count ${if (count == 1) "pornita" else "pornite"} · $watts W" to CMuted
    }
    SimpleCasaSurface(status = status, statusColor = statusColor) {
        plugs.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(CLine))
            SimpleCasaPlugRow(
                plug = p,
                on = optimistic[p.id] ?: p.on,
                stale = plugsStale,
                busy = p.id in optimistic,
                onToggle = { toggle(p) },
                onOpen = { onOpenPlug(p.id) }
            )
        }
        if (plugs.isEmpty()) Text("Astept lista de prize.", color = CMuted, fontSize = 13.sp)

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Senzori", color = CText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val s = sensors
            when {
                s == null -> Text("Astept date", color = CBat, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                !s.cloudOk -> Text("Cloud deconectat", color = CGrid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val list = sensors?.sensors.orEmpty()
        list.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(CLine))
            SimpleCasaSensorRow(s, cloudOk = sensors?.cloudOk == true, clock = clock, onOpen = { onOpenSensor(s.id) })
        }
        if (sensors != null && list.isEmpty()) Text("Fara senzori.", color = CMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SimpleCasaPlugRow(
    plug: CasaPlug,
    on: Boolean,
    stale: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    // Priza raportata offline primeste totusi comanda (serverul incearca sa se conecteze), ca pe pagina Retro:
    // comutatorul se blocheaza doar la date vechi sau comanda in zbor; `usable` conduce doar afisarea W.
    val usable = plug.online && !stale
    val canSwitch = !stale && !busy
    val badges = casaBadgeLines(plug, maxLines = 2)
    val power = plug.power
    val watts = if (usable && plug.metered && power != null) "${power.roundToInt()} W" else "--- W"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (plug.online) 1f else 0.55f)
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(plug.name, color = CText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            badges.forEach {
                Text(
                    it.text.lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() },
                    color = if (it.kind == CasaBadgeKind.OFFLINE) CGrid else CMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            watts,
            color = if (watts != "--- W" && on && (power ?: 0.0) > 0.5) CBat else CMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 58.dp)
        )
        Switch(
            checked = on,
            onCheckedChange = { if (canSwitch) onToggle() },
            enabled = canSwitch,
            modifier = Modifier.semantics { contentDescription = "${plug.name}: ${if (on) "opreste" else "porneste"}" }
        )
    }
}

@Composable
private fun SimpleCasaSensorRow(sensor: CasaSensor, cloudOk: Boolean, clock: State<Long>, onOpen: () -> Unit) {
    val nowSec = clock.value / 1000
    val stale = isStale(nowSec, sensor.updated)
    // Cloud cazut: valorile pot fi vechi, deci nu se arata ca proaspete.
    val showValues = cloudOk && sensor.updated != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(sensor.name, color = CText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (cloudOk) simpleAgeText(nowSec, sensor.updated) else "Cloud deconectat",
                color = if (stale || !cloudOk) CGrid else CMuted,
                fontSize = 11.sp,
                fontWeight = if (stale || !cloudOk) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
        val dim = if (stale) 0.5f else 1f
        Text(
            if (showValues) "${casaTemperatureText(sensor.temperature)} °C" else "— °C",
            color = simpleTemperatureColor(sensor.temperature.takeIf { showValues }),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.alpha(dim)
        )
        Text(
            if (showValues) sensor.humidity?.let { "${it.roundToInt()} %" } ?: "— %" else "— %",
            color = CHouse,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 44.dp).alpha(dim)
        )
    }
}

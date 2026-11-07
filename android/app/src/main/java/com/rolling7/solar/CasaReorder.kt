package com.rolling7.solar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs

/*
 * Mutarea prizelor din lista CASA, ca pe ecranul Android: apasare lunga pe un rand, vibratie,
 * randurile tremura, tragi randul sus/jos, la ridicarea degetului ordinea se trimite la server.
 * Gestul sta intr-un singur loc, deasupra listei si a senzorilor, in pasul Initial (ca ElasticZoom):
 * vede atingerea inaintea randurilor, iar de la apasarea lunga consuma tot, deci intrerupatorul si
 * deschiderea detaliului nu se mai declanseaza.
 */

/** Cat tine o jumatate de tremurat (rotatia merge de la -1 la +1 grad si inapoi). */
private const val WiggleHalfPeriodMs = 120
internal const val WiggleDegrees = 1f

/** Randul tras acum: ce rand, cat s-a mutat pe verticala, unde ar ateriza, si ultima asezare (animatie). */
internal class CasaDragState {
    var id by mutableStateOf<String?>(null)
        private set
    var from by mutableIntStateOf(0)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    /** Distanta dintre doua randuri vecine, in pixeli. */
    var pitch = 0f
        private set
    private var count = 0

    /** Pozitia pe care ajunge randul tras daca ar fi lasat acum (citita in compunere doar cand se schimba). */
    val target: Int by derivedStateOf { if (id == null) from else casaDropIndex(from, offset, pitch, count) }

    // Randul lasat: pleaca de la locul unde l-a lasat degetul si aluneca spre locul lui nou.
    var settleId: String? = null
        private set
    var settleFrom = 0f
        private set
    var settleToken by mutableIntStateOf(0)
        private set

    fun begin(id: String, from: Int, pitch: Float, count: Int) {
        this.pitch = pitch
        this.count = count
        this.from = from
        this.offset = 0f
        this.id = id
    }

    /** Randul tras nu iese din lista: ramane intre primul si ultimul loc. */
    fun move(totalPx: Float) {
        offset = totalPx.coerceIn(-from * pitch, (count - 1 - from) * pitch)
    }

    /** Degetul s-a ridicat: intoarce pozitia finala si porneste animatia de asezare. */
    fun drop(): Int {
        val t = target
        settleId = id
        settleFrom = offset - (t - from) * pitch
        settleToken++
        id = null
        offset = 0f
        return t
    }

    fun cancel() {
        id = null
        offset = 0f
    }
}

/**
 * Unde sunt randurile pe ecran, pentru gest. Nu e stare Compose: se citeste doar cand incepe o atingere.
 * Pozitiile se iau la cerere din `LayoutCoordinates` (mereu la zi, chiar dupa o mutare).
 */
internal class CasaRowGeometry {
    var host: LayoutCoordinates? = null
    var doneKey: LayoutCoordinates? = null
    val rows = HashMap<String, LayoutCoordinates>()

    private fun top(id: String): Float? = rows[id]?.takeIf { it.isAttached }?.positionInRoot()?.y

    /** Distanta dintre varfurile a doua randuri vecine (inaltimea randului plus linia dintre ele). */
    fun pitch(ids: List<String>): Float {
        if (ids.size < 2) return 0f
        val a = top(ids[0]) ?: return 0f
        val b = top(ids[1]) ?: return 0f
        return b - a
    }

    private fun toRoot(local: Offset): Offset? =
        host?.takeIf { it.isAttached }?.let { it.positionInRoot() + local }

    fun isOnDoneKey(local: Offset): Boolean {
        val key = doneKey?.takeIf { it.isAttached } ?: return false
        val p = toRoot(local) ?: return false
        val tl = key.positionInRoot()
        return p.x >= tl.x && p.x < tl.x + key.size.width && p.y >= tl.y && p.y < tl.y + key.size.height
    }

    /** Indexul randului de sub deget (linia de sub un rand ii apartine); -1 in afara randurilor sau pe GATA. */
    fun rowIndexAt(local: Offset, ids: List<String>): Int {
        val p = toRoot(local) ?: return -1
        if (isOnDoneKey(local)) return -1
        val pitch = pitch(ids)
        for ((i, id) in ids.withIndex()) {
            val c = rows[id]?.takeIf { it.isAttached } ?: continue
            val top = c.positionInRoot().y
            val height = if (i < ids.lastIndex && pitch > 0f) pitch else c.size.height.toFloat()
            if (p.y >= top && p.y < top + height) return i
        }
        return -1
    }
}

/**
 * Gestul de mutare. Intr-un singur `pointerInput`:
 *  - in afara modului de mutare: apasare lunga pe un rand (degetul sta pe loc) -> [onEnter], apoi
 *    randul urmeaza degetul fara sa-l ridici;
 *  - in modul de mutare: atingerea unui rand nu face nimic, tragerea lui il muta; atingerea scurta in afara
 *    randurilor (nu pe GATA) -> [onExit];
 *  - la ridicarea degetului, daca ordinea s-a schimbat -> [onDrop] cu noua ordine.
 * [isBusy] = o mutare anterioara inca se trimite: randurile nu se pot trage, dar atingerea nu trece prin ele.
 */
@Composable
internal fun rememberCasaReorderGestures(
    geometry: CasaRowGeometry,
    drag: CasaDragState,
    isReorder: () -> Boolean,
    isBusy: () -> Boolean,
    currentIds: () -> List<String>,
    onEnter: () -> Unit,
    onExit: () -> Unit,
    onDrop: (List<String>) -> Unit
): Modifier {
    val reorderNow by rememberUpdatedState(isReorder)
    val busyNow by rememberUpdatedState(isBusy)
    val idsNow by rememberUpdatedState(currentIds)
    val enterNow by rememberUpdatedState(onEnter)
    val exitNow by rememberUpdatedState(onExit)
    val dropNow by rememberUpdatedState(onDrop)
    return Modifier.pointerInput(geometry, drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val list = idsNow()
            val inReorder = reorderNow()
            val index = geometry.rowIndexAt(down.position, list)

            if (index < 0 || list.size < 2) {
                // Atingere scurta in afara randurilor, in modul de mutare: iesire (nu consumam nimic).
                if (inReorder && !geometry.isOnDoneKey(down.position)) {
                    var tap = false
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.isConsumed || e.changes.count { it.pressed } > 1) break
                        if ((c.position - down.position).getDistance() > slop) break
                        if (!c.pressed) {
                            tap = true
                            break
                        }
                    }
                    if (tap) exitNow()
                }
                return@awaitEachGesture
            }

            var lifted = false
            if (!inReorder) {
                // Apasare lunga: degetul sta pe loc, un singur deget, nimeni nu a consumat atingerea.
                val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id }
                        if (c == null || c.isConsumed || !c.pressed || e.changes.count { it.pressed } > 1 ||
                            (c.position - down.position).getDistance() > slop
                        ) {
                            break
                        }
                    }
                } == null
                    if (!timedOut) return@awaitEachGesture
                enterNow()
                // Ca pe ecranul Android: randul se ridica imediat, tragerea continua fara sa ridici degetul.
                if (!busyNow()) {
                    drag.begin(list[index], index, geometry.pitch(list), list.size)
                    lifted = true
                }
            } else {
                down.consume()
            }

            var total = 0f
            var released = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                val dy = c.position.y - c.previousPosition.y
                e.changes.forEach { it.consume() }
                if (!c.pressed) {
                    released = true
                    break
                }
                total += dy
                if (!lifted && !busyNow() && abs(total) > slop) {
                    drag.begin(list[index], index, geometry.pitch(list), list.size)
                    lifted = true
                }
                if (lifted) drag.move(total)
            }
            if (lifted) {
                if (released) {
                    val newIds = casaMove(list, index, drag.drop())
                    if (newIds != list) dropNow(newIds)
                } else {
                    drag.cancel()
                }
            }
        }
    }
}

/** Unghiul de tremurat (in grade, intre -[WiggleDegrees] si +[WiggleDegrees]); ruleaza doar cat e in compozitie. */
@Composable
internal fun rememberCasaWiggle(): State<Float> {
    val transition = rememberInfiniteTransition(label = "casa-wiggle")
    return transition.animateFloat(
        initialValue = -WiggleDegrees,
        targetValue = WiggleDegrees,
        animationSpec = infiniteRepeatable(tween(WiggleHalfPeriodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "casa-wiggle-angle"
    )
}

/** Butonul „GATA” care iese din modul de mutare (verde, ca alegerea activa din detaliu). */
@Composable
internal fun CasaDoneKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    RetroMetalButton(
        modifier = modifier,
        selected = true,
        accent = CasaGreenLit,
        description = "Gata, termina mutarea prizelor",
        onClick = onClick
    ) {
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(CasaGreenLit.copy(alpha = 0.80f), CasaGreenDark.copy(alpha = 0.90f)))
            )
        )
        Text(
            text = "GATA",
            color = Color(0xFFF2FFD0),
            fontFamily = RetroMono,
            fontSize = fixedSp(12f),
            fontWeight = FontWeight.Bold,
            letterSpacing = fixedSp(1f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

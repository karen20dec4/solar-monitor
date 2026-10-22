package com.rolling7.solar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.tanh

/** Cat de mult se poate mari ecranul cu doua degete (limita moale, nu un zid). */
internal const val ElasticZoomMax = 3f

/** Cat de mult "cedeaza" ecranul cand strangi degetele (fractie din miscarea degetelor, sub 1x). */
private const val ElasticShrinkSlope = 0.25f

/** Limitele factorului brut (produsul miscarilor degetelor), ca sa nu se "acumuleze" fara capat. */
private const val ElasticRawMin = 0.3f
private const val ElasticRawMax = 5f

/**
 * Din factorul brut al degetelor in factorul afisat. Pana pe la 1,2x urmareste degetele 1:1, apoi
 * se aseaza spre [ElasticZoomMax] (tanh) - ca un elastic care se intinde tot mai greu. Sub 1x
 * cedeaza doar un sfert din miscare.
 */
internal fun elasticScale(raw: Float): Float {
    val span = ElasticZoomMax - 1f
    return if (raw >= 1f) 1f + span * tanh((raw - 1f) / span) else 1f - ElasticShrinkSlope * (1f - raw)
}

/** Inversa lui [elasticScale]: factorul brut de la care porneste o noua prindere cand scara e deja != 1. */
internal fun elasticRawFor(scale: Float): Float {
    val span = ElasticZoomMax - 1f
    return if (scale >= 1f) {
        val x = ((scale - 1f) / span).coerceIn(0f, 0.999f)
        1f + span * 0.5f * ln((1f + x) / (1f - x))
    } else {
        1f - (1f - scale) / ElasticShrinkSlope
    }
}

/**
 * Zoom elastic cu doua degete peste tot continutul: cat timp tii degetele pe ecran, aplicatia se
 * mareste (si se muta odata cu degetele); cand le ridici, revine singura, cu un mic resort, la
 * marimea initiala. Nu schimba nimic permanent - nu exista "stare marita".
 *
 * Un deget singur trece nemodificat spre continut (atingeri, glisari intre taburi). De cand apar
 * doua degete, gestul e al zoom-ului: modificarile sunt consumate in pasul Initial, deci continutul
 * (butoane, navigarea prin glisare) primeste anulare si nu reactioneaza la ciupire.
 */
@Composable
internal fun ElasticZoom(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val settle = remember { arrayOfNulls<Job>(1) }

    fun release() {
        settle[0]?.cancel()
        val fromScale = scale
        val fromPan = pan
        settle[0] = scope.launch {
            animate(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
            ) { fraction, _ ->
                scale = 1f + (fromScale - 1f) * fraction
                pan = fromPan * fraction
            }
            scale = 1f
            pan = Offset.Zero
        }
    }

    Box(
        modifier
            .then(if (backgroundColor == Color.Unspecified) Modifier else Modifier.background(backgroundColor))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 0 = un deget (nu ne atingem de el), 1 = zoom in curs, 2 = zoom terminat, degete inca jos
                    var phase = 0
                    var raw = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2 && phase != 2) {
                            if (phase == 0) {
                                phase = 1
                                val settling = settle[0]?.isActive == true
                                settle[0]?.cancel()
                                // O prindere noua in timpul revenirii pastreaza pivotul si continua de la scara de acum.
                                if (!settling) {
                                    pivot = event.calculateCentroid(useCurrent = true)
                                    pan = Offset.Zero
                                }
                                raw = elasticRawFor(scale)
                            }
                            raw = (raw * event.calculateZoom()).coerceIn(ElasticRawMin, ElasticRawMax)
                            scale = elasticScale(raw)
                            pan += event.calculatePan()
                            event.changes.forEach { it.consume() }
                        } else if (phase != 0) {
                            // A ramas un singur deget: zoom-ul s-a terminat, ecranul revine; degetul ramas nu mai actioneaza.
                            if (phase == 1) {
                                phase = 2
                                release()
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (phase == 1) release()
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = pan.x
                translationY = pan.y
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (size.width > 0f) pivot.x / size.width else 0.5f,
                    pivotFractionY = if (size.height > 0f) pivot.y / size.height else 0.5f
                )
            }
    ) {
        content()
    }
}

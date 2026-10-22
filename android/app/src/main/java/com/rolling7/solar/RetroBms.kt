package com.rolling7.solar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/*
 * Bateria vazuta de BMS in tema Retro. Trei locuri:
 *  - TABLOU: banda de trei placi (SOC, curent, diferenta intre celule) sub Bat/Inv;
 *  - SISTEM: monitorul din locul fostei console (bare pe celule + cifrele importante);
 *  - SETARI: randul "Alarma baterie" cu intrerupator propriu.
 * Placile sunt aceeasi fotografie ca Bat/Inv (`retro_dashboard_dial_blank` = placa temperaturii fara
 * unitatea pictata, vezi scripts/make-retro-blank-dial.py); cifrele si unitatile sunt Compose nativ.
 */

internal fun retroBmsColor(level: BmsLevel): Color = when (level) {
    BmsLevel.OK -> RetroSage
    BmsLevel.WARNING -> RetroYellow
    BmsLevel.ALARM -> RetroRed
    BmsLevel.NO_DATA -> RetroOlive
}

/** Textul scurt de stare; la alarma arata chiar titlul trimis de server (ex. "BMS: bateria deconectata!"). */
internal fun retroBmsStatusText(state: BmsState?): String {
    val level = bmsLevel(state)
    return when (level) {
        BmsLevel.NO_DATA -> "FARA DATE"
        BmsLevel.OK -> "OK"
        BmsLevel.WARNING -> state?.alarms?.firstOrNull()?.title?.uppercase(Locale.ROOT) ?: "ATENTIE"
        BmsLevel.ALARM ->
            state?.alarms?.firstOrNull { it.rings }?.title?.uppercase(Locale.ROOT) ?: "BATERIE DECONECTATA"
    }
}

/** Culoarea unei cifre: rosu/galben dupa stare, dar nu aprinde SOC-ul de un avertisment de celule. */
private fun plateColor(level: BmsLevel, soft: BmsLevel = BmsLevel.OK): Color = when {
    level == BmsLevel.NO_DATA -> RetroOlive
    level == BmsLevel.ALARM -> RetroRed
    soft == BmsLevel.WARNING -> RetroYellow
    else -> RetroSage
}

/** TABLOU: titlu + LED de stare si trei placi. Atingerea duce pe SISTEM, unde e detaliul. */
@Composable
internal fun RetroBmsStrip(
    state: BmsState?,
    plateHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val reading = state?.reading
    val level = bmsLevel(state)
    val statusColor = retroBmsColor(level)
    val deltaLevel = if (reading != null && reading.deltaMv >= 50.0) BmsLevel.WARNING else BmsLevel.OK

    Column(
        modifier = modifier
            .semantics {
                contentDescription = if (reading == null) {
                    "Baterie BMS, fara date"
                } else {
                    "Baterie BMS, incarcare ${bmsSocText(reading.soc)} procente, curent ${bmsCurrentText(reading.current)} amperi, " +
                        "diferenta intre celule ${bmsDeltaText(reading.deltaMv)} milivolti, ${retroBmsStatusText(state)}"
                }
            }
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "BATERIE · BMS",
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 7.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                maxLines = 1
            )
            Text(
                retroBmsStatusText(state),
                color = statusColor,
                fontFamily = RetroMono,
                fontSize = 7.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            RetroLed(
                color = statusColor,
                active = level != BmsLevel.NO_DATA,
                modifier = Modifier.size(14.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().height(plateHeight),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RetroBmsPlate(
                label = "SOC",
                value = reading?.let { bmsSocText(it.soc) } ?: "—",
                unit = "%",
                color = plateColor(level),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            RetroBmsPlate(
                label = "I",
                value = reading?.let { bmsCurrentText(it.current) } ?: "—",
                unit = "A",
                color = plateColor(level),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            RetroBmsPlate(
                label = "Δ",
                value = reading?.let { bmsDeltaText(it.deltaMv) } ?: "—",
                unit = "mV",
                color = plateColor(level, deltaLevel),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/** O placa din fotografie cu fereastra VFD: eticheta mica in stanga, cifre in mijloc, unitatea in dreapta jos. */
@Composable
private fun RetroBmsPlate(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        Image(
            painter = painterResource(R.drawable.retro_dashboard_dial_blank),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = label,
            color = RetroSage.copy(alpha = 0.85f),
            fontFamily = RetroMono,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * 0.13f, y = -(maxHeight * 0.20f))
        )
        RetroVfdDisplay(
            value = value,
            unit = "",
            color = color,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * 0.25f)
                .width(maxWidth * 0.50f)
                .height(maxHeight * 0.66f),
            embedded = true,
            description = "$value $unit"
        )
        Text(
            text = unit,
            color = color.copy(alpha = 0.9f),
            fontFamily = RetroMono,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * 0.77f, y = maxHeight * 0.13f)
        )
    }
}

/**
 * SISTEM: in locul consolei. Sus titlul si starea, apoi o bara pe fiecare celula (abaterea de la
 * medie, ±15 mV scara plina), apoi cifrele importante. Daca e o alarma, ultimul rand devine mesajul ei.
 */
@Composable
internal fun RetroBmsMonitor(
    state: BmsState?,
    modifier: Modifier = Modifier
) {
    val reading = state?.reading
    val level = bmsLevel(state)
    val statusColor = retroBmsColor(level)
    val alarm = state?.alarms?.firstOrNull { it.rings } ?: state?.alarms?.firstOrNull()

    Column(
        modifier = modifier.semantics {
            contentDescription = if (reading == null) {
                "Baterie BMS, fara date"
            } else {
                "Baterie BMS, incarcare ${bmsSocText(reading.soc)} procente, ${reading.cells.size} celule, " +
                    "diferenta ${bmsDeltaText(reading.deltaMv)} milivolti"
            }
        }
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BATERIE · BMS",
                color = RetroMuted,
                fontFamily = RetroMono,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1
            )
            Text(
                retroBmsStatusText(state),
                color = statusColor,
                fontFamily = RetroMono,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)
            )
            RetroLed(
                color = statusColor,
                active = level != BmsLevel.NO_DATA,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        RetroCellBars(
            cells = reading?.cells ?: emptyList(),
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 18.dp)
        )
        Spacer(Modifier.height(3.dp))
        if (reading == null) {
            RetroBmsLine("ASTEPT DATE DE LA BMS", RetroOlive, 10)
        } else {
            val socColor = if (level == BmsLevel.ALARM) RetroRed else RetroSage
            RetroBmsLine(
                "SOC ${bmsSocText(reading.soc)}%  ${bmsCurrentText(reading.current)}A  " +
                    String.format(Locale.US, "%.2fV", reading.voltage),
                socColor,
                11
            )
            RetroBmsLine(
                "Δ${bmsDeltaText(reading.deltaMv)}mV  " +
                    String.format(Locale.US, "T %.1f/%.1f  MOS %.1f°C", reading.tempBattery1, reading.tempBattery2, reading.tempMos),
                if (reading.deltaMv >= 50.0 || reading.batteryTempMax >= 40.0) RetroYellow else RetroMuted,
                10
            )
            if (alarm != null) {
                RetroBmsLine("${alarm.title} ${alarm.message}".trim(), retroBmsColor(level), 9)
            } else {
                val mos = "INC ${if (reading.chargeOn) "ON" else "OFF"} DESC ${if (reading.dischargeOn) "ON" else "OFF"}"
                val balance = if (reading.balancing > 0) "ECH ACTIV" else "ECH -"
                val capacity = if (reading.remainingAh.isFinite() && reading.nominalAh.isFinite()) {
                    String.format(Locale.US, "%.0f/%.0fAh", reading.remainingAh, reading.nominalAh)
                } else ""
                RetroBmsLine("$mos · $balance · $capacity · ${reading.cycles}cicl".trim(), RetroOlive, 9)
            }
        }
    }
}

@Composable
private fun RetroBmsLine(text: String, color: Color, sizeSp: Int) {
    Text(
        text,
        color = color,
        fontFamily = RetroMono,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp + 2).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = LocalTextStyle.current.copy(
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
        )
    )
}

/** Cate o bara pe celula, pornind dintr-o linie de mijloc: sus = peste medie, jos = sub medie. */
@Composable
private fun RetroCellBars(cells: List<Double>, modifier: Modifier = Modifier) {
    val fractions = cellDeviationFractions(cells)
    val avg = if (cells.isEmpty()) 0.0 else cells.average()
    Canvas(modifier) {
        drawRoundRect(color = Color.Black.copy(alpha = 0.18f), cornerRadius = CornerRadius(4.dp.toPx()))
        val mid = size.height / 2f
        drawLine(
            color = RetroOlive.copy(alpha = 0.35f),
            start = Offset(0f, mid),
            end = Offset(size.width, mid),
            strokeWidth = 0.7.dp.toPx()
        )
        if (fractions.isEmpty()) return@Canvas
        val slot = size.width / fractions.size
        val barWidth = (slot * 0.56f).coerceAtMost(14.dp.toPx())
        val half = mid - 3.dp.toPx()
        fractions.forEachIndexed { index, fraction ->
            val deviationMv = kotlin.math.abs((cells[index] - avg) * 1000.0)
            val color = when {
                deviationMv >= 30.0 -> RetroRed
                deviationMv >= 10.0 -> RetroYellow
                else -> RetroSage
            }
            val x = slot * index + (slot - barWidth) / 2f
            // Orice celula are cel putin o dunga subtire, ca sa se vada ca exista.
            val h = (kotlin.math.abs(fraction) * half).coerceAtLeast(1.2.dp.toPx())
            val top = if (fraction >= 0f) mid - h else mid
            drawRoundRect(
                color = color.copy(alpha = 0.88f),
                topLeft = Offset(x, top),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(1.5.dp.toPx())
            )
        }
    }
}

/** SETARI: randul "Alarma baterie" - titlu, stare si intrerupatorul din fotografie. */
@Composable
internal fun RetroBatteryAlarmRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    RetroInsetSurface(
        modifier = modifier.semantics { contentDescription = "Setari alarma baterie" },
        shape = RoundedCornerShape(10.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val toggleHeight = maxHeight * 0.62f
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
            ) {
                Text(
                    "ALARMĂ BATERIE",
                    color = RetroBrassLight,
                    fontFamily = RetroMono,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                Text(
                    if (enabled) "ACTIVĂ · BMS, TEMPERATURĂ, CELULE" else "OPRITĂ",
                    color = if (enabled) RetroSage else RetroBrassLight.copy(alpha = 0.7f),
                    fontFamily = RetroMono,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
            RetroSettingsAnimatedToggle(
                checked = enabled,
                description = if (enabled) "Alarma baterie activa" else "Alarma baterie oprita",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .height(toggleHeight)
                    .width(toggleHeight * (400f / 137f)),
                onClick = { onEnabledChange(!enabled) }
            )
        }
    }
}

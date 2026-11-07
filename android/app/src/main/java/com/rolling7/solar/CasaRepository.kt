package com.rolling7.solar

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface CasaResult<out T> {
    data class Ok<T>(val value: T) : CasaResult<T>
    object Unauthorized : CasaResult<Nothing>
    data class Error(val message: String) : CasaResult<Nothing>
}

/**
 * Traduce un raspuns HTTP de eroare in rezultat. Mesajele sunt majuscule fara diacritice (ecran Retro).
 * 401 pe o ruta cu jeton = jeton revocat sau necunoscut -> [CasaResult.Unauthorized]. La inregistrare
 * ([authenticated] = false) un 401 inseamna parola gresita -> `Error("PAROLA GRESITA")`, niciodata
 * Unauthorized (care e rezervat jetonului). 400/404/503 = textul `error` al serverului, transformat cu
 * [retroLabel] (400 fara text: "CERERE INVALIDA"). 502 cu cheia `plug` in JSON = priza nu raspunde;
 * 502/503/504 fara JSON (Caddy, serviciul oprit) = "SERVER INDISPONIBIL".
 */
internal fun casaFailure(code: Int, body: String, authenticated: Boolean = true): CasaResult<Nothing> {
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        null
    }
    val msg = json?.stringOrNull("error")?.takeIf { it.isNotBlank() }?.let { retroLabel(it) }
    return when (code) {
        401 -> if (authenticated) CasaResult.Unauthorized else CasaResult.Error("PAROLA GRESITA")
        429 -> CasaResult.Error("PREA MULTE INCERCARI")
        502 -> CasaResult.Error(if (json != null && json.has("plug")) "PRIZA NU RASPUNDE" else "SERVER INDISPONIBIL")
        503 -> CasaResult.Error(msg ?: "SERVER INDISPONIBIL")
        504 -> CasaResult.Error("SERVER INDISPONIBIL")
        404 -> CasaResult.Error(msg ?: "NEGASIT")
        400 -> CasaResult.Error(msg ?: "CERERE INVALIDA")
        else -> CasaResult.Error("EROARE SERVER")
    }
}

/**
 * Clientul HTTP pentru rutele `/solar/casa`. Apeluri sincrone - se fac pe Dispatchers.IO.
 * Parola si jetonul nu se scriu niciodata in log.
 */
object CasaRepository {
    // Productie; doar build-ul debug poate primi alt URL (-PcasaBase), vezi app/build.gradle.kts.
    private val BASE = BuildConfig.CASA_BASE
    private const val READ_MS = 8000
    // Cel mai rau caz teoretic pe server e ~45 s (coada prizei); Caddy asteapta 60 s.
    private const val COMMAND_READ_MS = 50000

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun cleanDevice(v: String?) =
        (v ?: "").replace(Regex("[^\\w .\\-+]"), "").trim().ifEmpty { "necunoscut" }

    /** Numele telefonului trimis la inregistrare, curatat ca antetul User-Agent. */
    fun deviceName(): String = cleanDevice(Build.MODEL)

    private fun <T> call(
        method: String,
        path: String,
        token: String?,
        body: JSONObject?,
        readMs: Int,
        parse: (String) -> T
    ): CasaResult<T> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                // Jetonul nu pleaca pe alt URL; un redirect e tratat ca eroare.
                instanceFollowRedirects = false
                connectTimeout = 5000
                readTimeout = readMs
                requestMethod = method
                setRequestProperty("User-Agent", SolarRepository.userAgent)
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                try {
                    CasaResult.Ok(parse(text))
                } catch (e: Exception) {
                    CasaResult.Error("RASPUNS INVALID")
                }
            } else {
                val text = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } catch (e: IOException) {
                    ""
                }
                casaFailure(code, text, token != null)
            }
        } catch (e: Exception) {
            // Unele stive HttpURLConnection arunca asta la un 401 fara WWW-Authenticate.
            if (e is IOException && e.message?.contains("authentication challenge", ignoreCase = true) == true) {
                casaFailure(401, "", token != null)
            } else {
                CasaResult.Error("FARA LEGATURA")
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun command(
        method: String, token: String, id: String, sub: String, body: JSONObject?
    ): CasaResult<CasaPlug> =
        call(method, "/plugs/${enc(id)}$sub", token, body, COMMAND_READ_MS) { parsePlug(it) }

    /**
     * Schimba parola comuna cu un jeton propriu telefonului. Un 401 aici inseamna parola gresita si vine ca
     * `Error("PAROLA GRESITA")`, nu ca [CasaResult.Unauthorized] (rezervat jetonului respins).
     */
    fun register(password: String, device: String): CasaResult<String> =
        call("POST", "/register", null, JSONObject().put("password", password).put("device", device), READ_MS) {
            JSONObject(it).getString("token")
        }

    fun plugs(token: String): CasaResult<List<CasaPlug>> =
        call("GET", "/plugs", token, null, READ_MS) { parsePlugs(it) }

    fun switch(token: String, id: String, on: Boolean): CasaResult<CasaPlug> =
        command("POST", token, id, "/switch", JSONObject().put("on", on))

    fun setCountdown(token: String, id: String, action: String, minutes: Int): CasaResult<CasaPlug> =
        command("PUT", token, id, "/countdown", JSONObject().put("action", action).put("minutes", minutes))

    fun cancelCountdown(token: String, id: String): CasaResult<CasaPlug> =
        command("DELETE", token, id, "/countdown", null)

    fun addEvent(token: String, id: String, time: String, action: String, days: List<Int>?): CasaResult<CasaPlug> =
        command("POST", token, id, "/events", casaEventBody(time, action, days))

    /** `daily = true` trimite `"days": null` (zilnic); fara `daily`, `days` null lasa zilele neschimbate. */
    fun updateEvent(
        token: String, id: String, eid: String,
        time: String? = null, action: String? = null, days: List<Int>? = null,
        daily: Boolean = false, enabled: Boolean? = null
    ): CasaResult<CasaPlug> =
        command("PUT", token, id, "/events/${enc(eid)}", casaEventUpdateBody(time, action, days, daily, enabled))

    fun deleteEvent(token: String, id: String, eid: String): CasaResult<CasaPlug> =
        command("DELETE", token, id, "/events/${enc(eid)}", null)

    fun setInching(token: String, id: String, enabled: Boolean, minutes: Int): CasaResult<CasaPlug> =
        command("PUT", token, id, "/inching", JSONObject().put("enabled", enabled).put("minutes", minutes))

    /** Numele dat din aplicatie (salvat pe server, nu si in SmartLife). */
    fun renamePlug(token: String, id: String, name: String): CasaResult<CasaPlug> =
        command("PUT", token, id, "/name", JSONObject().put("name", name))

    fun renameSensor(token: String, id: String, name: String): CasaResult<String> =
        call("PUT", "/sensors/${enc(id)}/name", token, JSONObject().put("name", name), READ_MS) {
            JSONObject(it).getString("name")
        }

    fun sensors(token: String): CasaResult<CasaSensors> =
        call("GET", "/sensors", token, null, READ_MS) { parseSensors(it) }

    fun sensorHistory(token: String, id: String, range: String): CasaResult<CasaSensorHistory> =
        call("GET", "/sensors/${enc(id)}/history?range=${enc(range)}", token, null, READ_MS) {
            parseSensorHistory(it)
        }
}

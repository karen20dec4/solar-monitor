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
 * Unauthorized (care e rezervat jetonului). 400 = mesajul serverului, transformat cu [retroLabel].
 */
internal fun casaFailure(code: Int, body: String, authenticated: Boolean = true): CasaResult<Nothing> = when (code) {
    401 -> if (authenticated) CasaResult.Unauthorized else CasaResult.Error("PAROLA GRESITA")
    429 -> CasaResult.Error("PREA MULTE INCERCARI")
    502 -> CasaResult.Error("PRIZA NU RASPUNDE")
    404 -> CasaResult.Error("PRIZA NECUNOSCUTA")
    503 -> CasaResult.Error("SERVER INDISPONIBIL")
    400 -> {
        val msg = try {
            JSONObject(body).stringOrNull("error") ?: ""
        } catch (e: Exception) {
            ""
        }
        CasaResult.Error(if (msg.isBlank()) "CERERE INVALIDA" else retroLabel(msg))
    }
    else -> CasaResult.Error("EROARE SERVER")
}

/**
 * Clientul HTTP pentru rutele `/solar/casa`. Apeluri sincrone - se fac pe Dispatchers.IO.
 * Parola si jetonul nu se scriu niciodata in log.
 */
object CasaRepository {
    private const val BASE = "https://vyra.go.ro:31443/solar/casa"
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

    fun addInterval(token: String, id: String, start: String, stop: String): CasaResult<CasaPlug> =
        command("POST", token, id, "/schedules", JSONObject().put("start", start).put("stop", stop))

    fun updateInterval(
        token: String, id: String, sid: String,
        enabled: Boolean? = null, start: String? = null, stop: String? = null
    ): CasaResult<CasaPlug> {
        val body = JSONObject()
        if (enabled != null) body.put("enabled", enabled)
        if (start != null) body.put("start", start)
        if (stop != null) body.put("stop", stop)
        return command("PUT", token, id, "/schedules/${enc(sid)}", body)
    }

    fun deleteInterval(token: String, id: String, sid: String): CasaResult<CasaPlug> =
        command("DELETE", token, id, "/schedules/${enc(sid)}", null)

    fun setInching(token: String, id: String, enabled: Boolean, minutes: Int): CasaResult<CasaPlug> =
        command("PUT", token, id, "/inching", JSONObject().put("enabled", enabled).put("minutes", minutes))

    fun sensors(token: String): CasaResult<CasaSensors> =
        call("GET", "/sensors", token, null, READ_MS) { parseSensors(it) }

    fun sensorHistory(token: String, id: String, range: String): CasaResult<CasaSensorHistory> =
        call("GET", "/sensors/${enc(id)}/history?range=${enc(range)}", token, null, READ_MS) {
            parseSensorHistory(it)
        }
}

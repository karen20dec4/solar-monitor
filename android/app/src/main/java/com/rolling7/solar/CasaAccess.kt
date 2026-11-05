package com.rolling7.solar

import android.content.Context
import android.content.SharedPreferences

/**
 * Jetonul acestui telefon pentru comenzile CASA (primit de la server pe baza parolei comune).
 * Fisierul `casa_access` e exclus din backup (vezi res/xml/backup_rules.xml si data_extraction_rules.xml).
 * Un raspuns 401 il sterge, ca aplicatia sa ceara parola o singura data si sa nu repete cererile.
 */
class CasaAccess(private val prefs: SharedPreferences) {
    val token: String? get() = prefs.getString(KEY_TOKEN, null)

    fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /**
     * Sterge jetonul doar daca serverul a respins chiar jetonul retinut. Un 401 tarziu pentru un jeton
     * vechi (cererea a plecat inainte de o noua inregistrare) nu are voie sa-l stearga pe cel nou.
     */
    fun onResult(usedToken: String, r: CasaResult<*>) {
        if (r is CasaResult.Unauthorized && token == usedToken) forget()
    }

    companion object {
        const val PREFS = "casa_access"
        private const val KEY_TOKEN = "token"
    }
}

fun casaAccess(context: Context): CasaAccess =
    CasaAccess(context.applicationContext.getSharedPreferences(CasaAccess.PREFS, Context.MODE_PRIVATE))

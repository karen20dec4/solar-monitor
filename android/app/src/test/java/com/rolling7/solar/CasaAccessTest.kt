package com.rolling7.solar

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SharedPreferences minimal, in memorie, doar cat foloseste CasaAccess. */
private class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun apply() {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
        override fun commit(): Boolean { apply(); return true }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }
    override fun getAll(): MutableMap<String, *> = map
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
}

class CasaAccessTest {
    @Test
    fun `starts without a token`() {
        assertNull(CasaAccess(FakePrefs()).token)
    }

    @Test
    fun `saved token is read back and survives a new instance`() {
        val prefs = FakePrefs()
        CasaAccess(prefs).save("abc")
        assertEquals("abc", CasaAccess(prefs).token)
    }

    @Test
    fun `unauthorized clears the token`() {
        val prefs = FakePrefs()
        val access = CasaAccess(prefs)
        access.save("abc")
        access.onResult("abc", CasaResult.Unauthorized)
        assertNull(access.token)
        assertNull(CasaAccess(prefs).token)
    }

    @Test
    fun `ok and errors keep the token`() {
        val access = CasaAccess(FakePrefs())
        access.save("abc")
        access.onResult("abc", CasaResult.Ok(1))
        assertEquals("abc", access.token)
        access.onResult("abc", CasaResult.Error("FARA LEGATURA"))
        assertEquals("abc", access.token)
    }

    @Test
    fun `late 401 for an old token keeps the new token`() {
        val access = CasaAccess(FakePrefs())
        access.save("old")
        access.save("new")
        access.onResult("old", CasaResult.Unauthorized)
        assertEquals("new", access.token)
        access.onResult("new", CasaResult.Unauthorized)
        assertNull(access.token)
    }

    @Test
    fun `forget clears the token`() {
        val access = CasaAccess(FakePrefs())
        access.save("abc")
        access.forget()
        assertNull(access.token)
    }
}

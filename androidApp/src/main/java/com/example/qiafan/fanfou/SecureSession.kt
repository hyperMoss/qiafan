package com.example.qiafan.fanfou

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class HistoryEntry(val kind: String, val id: String, val title: String)

internal class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("fanfou_session", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("fanfou_settings", Context.MODE_PRIVATE)
    private val alias = "qiafan_fanfou_session"

    init {
        if (prefs.contains("account") || prefs.contains("pending")) {
            prefs.edit().remove("account").remove("pending").apply()
        }
    }

    var showHot: Boolean
        get() = settings.getBoolean("show_hot", true)
        set(value) { settings.edit().putBoolean("show_hot", value).apply() }

    fun credentials(): FanfouCredentials? {
        val encrypted = prefs.getString("nofan_token", null) ?: return null
        return runCatching {
            val obj = JSONObject(decrypt(encrypted))
            NofanApplication.credentials(
                OAuthToken(obj.getString("token"), obj.getString("tokenSecret"))
            )
        }.getOrNull()
    }

    fun save(account: FanfouCredentials) {
        require(account.consumerKey == NofanApplication.consumerKey &&
            account.consumerSecret == NofanApplication.consumerSecret &&
            account.token.isNotBlank() && account.tokenSecret.isNotBlank())
        val obj = JSONObject()
            .put("token", account.token)
            .put("tokenSecret", account.tokenSecret)
        prefs.edit().remove("account").remove("pending")
            .putString("nofan_token", encrypt(obj.toString())).apply()
    }

    fun history(): List<HistoryEntry> {
        val array = runCatching {
            JSONArray(settings.getString("history", "[]"))
        }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            HistoryEntry(obj.optString("kind"), obj.optString("id"), obj.optString("title"))
        }
    }

    fun remember(entry: HistoryEntry) {
        if (entry.id.isBlank()) return
        val all = listOf(entry) + history().filterNot { it.kind == entry.kind && it.id == entry.id }
        val array = JSONArray()
        all.take(50).forEach {
            array.put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title))
        }
        settings.edit().putString("history", array.toString()).apply()
    }

    fun clearHistory() {
        settings.edit().remove("history").apply()
    }

    fun recentSearches(): List<String> {
        val array = runCatching { JSONArray(settings.getString("recent_searches", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    fun rememberSearch(query: String) {
        val value = query.trim()
        if (value.isBlank()) return
        val array = JSONArray()
        (listOf(value) + recentSearches().filterNot { it == value }).take(8).forEach(array::put)
        settings.edit().putString("recent_searches", array.toString()).apply()
    }

    fun clearRecentSearches() {
        settings.edit().remove("recent_searches").apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
        clearHistory()
        clearRecentSearches()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

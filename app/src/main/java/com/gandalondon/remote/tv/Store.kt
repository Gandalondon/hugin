package com.gandalondon.remote.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved pairing and preferences.
 *
 * The TV's client key is the credential that lets this phone control the TV
 * without a prompt, so it is encrypted with an AES key held in the Android
 * Keystore (hardware-backed on Pixel) and never leaves the device.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    var host: String?
        get() = prefs.getString("host", null)
        set(v) = prefs.edit().putString("host", v).apply()

    var tvName: String?
        get() = prefs.getString("tv_name", null)
        set(v) = prefs.edit().putString("tv_name", v).apply()

    /** SHA-256 of the TV's TLS certificate, pinned on first pairing. */
    var certPin: String?
        get() = prefs.getString("cert_pin", null)
        set(v) = prefs.edit().putString("cert_pin", v).apply()

    /** The TV's network card addresses, for Wake-on-LAN. */
    var macs: List<String>
        get() = prefs.getString("macs", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit().putString("macs", v.joinToString(",")).apply()

    var clientKey: String?
        get() = prefs.getString("client_key", null)?.let { runCatching { Vault.decrypt(it) }.getOrNull() }
        set(v) = prefs.edit().putString("client_key", v?.let { Vault.encrypt(it) }).apply()

    fun override(target: Target): String? = prefs.getString("map_${target.name}", null)

    fun setOverride(target: Target, inputId: String?) =
        prefs.edit().putString("map_${target.name}", inputId).apply()

    fun overrides(): Map<Target, String> =
        Target.entries.mapNotNull { t -> override(t)?.let { t to it } }.toMap()

    /** Drop the pairing but keep the TV address and input choices. */
    fun clearPairing() {
        prefs.edit().remove("client_key").remove("cert_pin").apply()
    }

    fun forgetTv() {
        prefs.edit().clear().apply()
    }
}

private object Vault {
    private const val ALIAS = "tv_pairing_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}

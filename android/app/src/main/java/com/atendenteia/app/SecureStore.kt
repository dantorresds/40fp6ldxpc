package com.atendenteia.app

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
 * Guarda segredos (token do Mercado Pago) criptografados com uma chave do
 * chaveiro do Android (AndroidKeyStore). A chave nunca sai do aparelho e não
 * vai para backup; sem ela o valor salvo é ilegível.
 */
object SecureStore {
    private const val ALIAS = "atendente_segredos"
    private const val PREFS = "atendente_seguro"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isBlank()) { p.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        p.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, name: String): String {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun has(ctx: Context, name: String) = get(ctx, name).isNotBlank()
}

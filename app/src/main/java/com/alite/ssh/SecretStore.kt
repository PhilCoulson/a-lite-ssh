package com.alite.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savePassword(plain: String) {
        if (plain.isEmpty()) {
            clearPassword()
            return
        }
        val existing = decryptStored()
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val keepStamp = existing == plain && savedAt > 0L && !isExpired(savedAt)
        val stamp = if (keepStamp) savedAt else System.currentTimeMillis()
        val blob = encrypt(plain)
        if (blob == null) {
            clearPassword()
            return
        }
        prefs.edit()
            .putString(KEY_PASSWORD, blob)
            .putLong(KEY_SAVED_AT, stamp)
            .apply()
    }

    fun loadPassword(): Memory {
        val blob = prefs.getString(KEY_PASSWORD, null)
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (blob.isNullOrEmpty() || savedAt <= 0L) {
            return Memory.Empty
        }
        if (isExpired(savedAt)) {
            clearPassword()
            return Memory.Expired
        }
        val plain = decrypt(blob)
        if (plain.isNullOrEmpty()) {
            clearPassword()
            return Memory.Empty
        }
        return Memory.Valid(plain)
    }

    fun clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).remove(KEY_SAVED_AT).apply()
    }

    private fun decryptStored(): String? {
        val blob = prefs.getString(KEY_PASSWORD, null) ?: return null
        return decrypt(blob)
    }

    private fun isExpired(savedAt: Long): Boolean =
        System.currentTimeMillis() - savedAt > TTL_MS

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
        Base64.encodeToString(packed, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    private fun decrypt(blob: String): String? = try {
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_BYTES),
            )
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    sealed class Memory {
        data class Valid(val password: String) : Memory()
        data object Expired : Memory()
        data object Empty : Memory()
    }

    companion object {
        const val TTL_DAYS = 7
        private const val TTL_MS = TTL_DAYS * 24L * 60L * 60L * 1000L
        private const val PREFS = "secrets"
        private const val KEY_PASSWORD = "ssh_password"
        private const val KEY_SAVED_AT = "ssh_password_saved_at"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "alite_ssh_secret"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

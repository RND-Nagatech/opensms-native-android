package org.daslearning.opensms.next.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import org.daslearning.opensms.next.domain.TokenVerifier

class AndroidTokenVerifier : TokenVerifier {
    private val random = SecureRandom()

    override fun createToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    override fun verifier(token: String): String {
        return hmac("api-token\u0000$token")
    }

    override fun fingerprintPayload(payload: String): String = hmac("message-payload\u0000$payload")

    private fun hmac(value: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(getOrCreateKey())
        return Base64.encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opensms_api_token_verifier_v1"
        const val HMAC = "HmacSHA256"
    }
}

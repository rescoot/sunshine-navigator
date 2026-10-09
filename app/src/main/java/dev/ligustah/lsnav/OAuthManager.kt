package dev.ligustah.lsnav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import net.openid.appauth.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class OAuthLoginRequired : Exception("Sign in to Sunshine again.")

class OAuthManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(context.noBackupFilesDir, "oauth-session"))
    private val mutex = Mutex()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    @Synchronized private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    @Synchronized private fun write(record: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(record.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }

    suspend fun authorizationIntent(baseUrl: String, clientId: String, service: AuthorizationService): Intent = mutex.withLock {
        val root = serverRoot(baseUrl)
        require(clientId.isNotBlank()) { "Enter a public OAuth client ID for this server." }
        val request = authorizationRequest(baseUrl, clientId)
        val record = try { read() } catch (_: Exception) { JSONObject() }
        record.put("pending", request.jsonSerialize()).put("pending_root", root)
        write(record)
        service.getAuthorizationRequestIntent(request)
    }

    suspend fun complete(intent: Intent?): AppConfiguration = mutex.withLock {
        val record = read()
        val pending = record.optJSONObject("pending") ?: throw OAuthLoginRequired()
        record.remove("pending")
        val root = record.getString("pending_root")
        record.remove("pending_root")
        write(record)
        val expected = AuthorizationRequest.jsonDeserialize(pending)
        val response = intent?.let(AuthorizationResponse::fromIntent)
        require(response != null && response.state == expected.state && response.request.jsonSerializeString() == expected.jsonSerializeString()) {
            "Sign-in was cancelled or the response could not be verified. Please try again."
        }
        val state = AuthState(response, null)
        val service = AuthorizationService(context)
        try {
            withContext(NonCancellable) {
                suspendCoroutine<Unit> { continuation ->
                    service.performTokenRequest(response.createTokenExchangeRequest(), NoClientAuthentication.INSTANCE) { token, error ->
                        if (token == null || error != null) continuation.resumeWithException(OAuthLoginRequired())
                        else { state.update(token, null); continuation.resume(Unit) }
                    }
                }
            }
            require(state.isAuthorized && !state.accessToken.isNullOrBlank()) { "Sign-in did not return an access token." }
            val scopes = grantedScopes(state.scope ?: response.scope)
            val id = UUID.randomUUID().toString()
            write(record.put("id", id).put("root", root).put("state", state.jsonSerialize()).put("scopes", scopes.sorted().joinToString(" ")))
            AppConfiguration(baseUrl = ApiClientProvider.normalizeBaseUrl(root), oauthSessionId = id, oauthClientId = expected.clientId, grantedScopes = scopes)
        } finally { service.dispose() }
    }

    suspend fun accessToken(configuration: AppConfiguration, forceRefresh: Boolean = false): String = mutex.withLock {
        val record = try { read() } catch (_: Exception) { throw OAuthLoginRequired() }
        if (record.optString("id") != configuration.oauthSessionId || record.optString("root") != serverRoot(configuration.baseUrl)) throw OAuthLoginRequired()
        val state = AuthState.jsonDeserialize(record.getJSONObject("state"))
        if (forceRefresh) state.setNeedsTokenRefresh(true)
        val service = AuthorizationService(context)
        try {
            // A rotated refresh token must be persisted even if the caller leaves the screen.
            withContext(NonCancellable) {
                suspendCoroutine<String> { continuation ->
                    state.performActionWithFreshTokens(service, NoClientAuthentication.INSTANCE) { token, _, error ->
                        try {
                            write(record.put("state", state.jsonSerialize()))
                            if (error != null || token.isNullOrBlank()) continuation.resumeWithException(OAuthLoginRequired())
                            else continuation.resume(token)
                        } catch (failure: Exception) { continuation.resumeWithException(failure) }
                    }
                }
            }
        } finally { service.dispose() }
    }

    suspend fun clear() = mutex.withLock { file.delete() }

    companion object {
        const val DEFAULT_CLIENT_ID = "g_YyrEsELn5DvWju1pc6zx-pLVGRTFNXsTMueslc7As"
        const val REDIRECT_URI = "dev.ligustah.lsnav:/oauth2redirect"
        private const val KEY_ALIAS = "lsnav-oauth"
        @Volatile private var instance: OAuthManager? = null
        fun get(context: Context): OAuthManager = instance ?: synchronized(this) {
            instance ?: OAuthManager(context).also { instance = it }
        }
        fun grantedScopes(scope: String?): Set<String> = scope.orEmpty().split(' ').filter(String::isNotBlank).toSet()

        fun authorizationRequest(baseUrl: String, clientId: String): AuthorizationRequest {
            val root = serverRoot(baseUrl)
            require(clientId.isNotBlank()) { "Enter a public OAuth client ID for this server." }
            val configuration = AuthorizationServiceConfiguration(Uri.parse("$root/oauth/authorize"), Uri.parse("$root/oauth/token"))
            return AuthorizationRequest.Builder(configuration, clientId.trim(), ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI))
                .setScopes("read", "write", "scooter_control").setState(UUID.randomUUID().toString()).build()
        }
        fun serverRoot(baseUrl: String): String = ApiClientProvider.normalizeBaseUrl(baseUrl).removeSuffix("/api/v1")
    }
}

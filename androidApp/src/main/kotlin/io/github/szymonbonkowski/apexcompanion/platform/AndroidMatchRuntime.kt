package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.szymonbonkowski.apexcompanion.history.HistoryIdentity
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.matches.*
import io.github.szymonbonkowski.apexcompanion.ranked.ApexPlatform
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val MATCH_DISK_MAX_BYTES = 16_777_216

/** Separate private atomic file; never changes the proven v0.3 history file or its 4 MiB limit. */
class AndroidMatchStore(file: File, private val maxBytes: Int = MATCH_DISK_MAX_BYTES): RotationStore {
    init { require(maxBytes in 1..MATCH_DISK_MAX_BYTES) }
    private val atomic = AtomicFile(file)
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        synchronized(atomic) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) null
            else atomic.openRead().use { readBounded(it, maxBytes).decodeToString(throwOnInvalidSequence = true) }
        }
    }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= maxBytes)
        synchronized(atomic) {
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
    }
}

internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= maxBytes) { "Private payload exceeds capacity" }
    }
    return output.toByteArray()
}

/** Credentials encrypted by a non-exportable Android Keystore key in backup-excluded storage. */
class AndroidMatchSyncPlatform(private val context: Context, namespace: String = "match-pairing-v1"): MatchSyncPlatform {
    init { require(namespace.length in 1..100 && namespace.all { it.isLetterOrDigit() || it == '-' }) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.enc"))
    private val alias = "apex-companion-$namespace"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    override suspend fun loadPairing(): MatchPairing? = withContext(Dispatchers.IO) {
        synchronized(file) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
            val bytes = file.openRead().use { readBounded(it, 16_384) }
            require(bytes.size > 28 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD("ApexCompanionMatchPairing:v1".encodeToByteArray())
            decodePairing(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString(throwOnInvalidSequence = true))
        }
    }
    override suspend fun savePairing(value: MatchPairing?) = withContext(Dispatchers.IO) {
        synchronized(file) {
            if (value == null) { file.delete(); check(!file.baseFile.exists()); return@synchronized }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD("ApexCompanionMatchPairing:v1".encodeToByteArray())
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(encodePairing(value).encodeToByteArray())
            require(bytes.size <= 16_384)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        }
    }
    override suspend fun authorize(): Boolean = AndroidMatchNetworkPermission.authorize(context)
    override suspend fun request(pairing: MatchPairing, path: String, body: String?): String = withContext(Dispatchers.IO) {
        check(AndroidMatchNetworkPermission.granted(context)) { "Local network permission is required" }
        require(path.matches(Regex("/v1/matches\\?after=[0-9]{1,19}")) || path == "/v1/ack" || path == "/v1/unpair")
        val trust = object: X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = error("Client certificates unsupported")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                require(!chain.isNullOrEmpty())
                val certificate = chain[0]
                certificate.checkValidity()
                val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
                require(MessageDigest.isEqual(pin.encodeToByteArray(), pairing.certificatePin.encodeToByteArray())) { "Pinned server identity mismatch" }
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        val connection = URL(pairing.endpoint + path).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = tls.socketFactory
            // Keep platform hostname/SAN validation in addition to the out-of-band DER pin.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.encodeToByteArray(); require(bytes.size <= 1024)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            require(connection.responseCode in 200..299) { "Companion rejected request" }
            val length = connection.contentLengthLong
            require(length <= 1_048_576)
            connection.inputStream.use { readBounded(it, 1_048_576).decodeToString(throwOnInvalidSequence = true) }
        } finally { connection.disconnect() }
    }
}

private fun encodePairing(p: MatchPairing) = buildJsonObject {
    put("endpoint", p.endpoint); put("certificatePin", p.certificatePin); put("token", p.token); put("companionId", p.companionId)
    put("identity", p.identity?.let { buildJsonObject { put("uid", it.uid); put("platform", it.platform.name) } } ?: JsonNull)
}.toString()
private fun decodePairing(value: String): MatchPairing {
    val j = Json.parseToJsonElement(value).jsonObject
    require(j.keys == setOf("endpoint", "certificatePin", "token", "companionId", "identity"))
    fun text(key: String) = j.getValue(key).jsonPrimitive.also { require(it.isString) }.content
    val identity = j.getValue("identity").takeIf { it != JsonNull }?.jsonObject?.let {
        require(it.keys == setOf("uid", "platform"))
        HistoryIdentity(it.getValue("uid").jsonPrimitive.content, ApexPlatform.valueOf(it.getValue("platform").jsonPrimitive.content))
    }
    return MatchPairing(text("endpoint"), text("certificatePin"), text("token"), text("companionId"), identity)
}

class AndroidMatchRuntime private constructor(context: Context) {
    private val clock = RotationClock { System.currentTimeMillis() / 1000 }
    val repository = MatchRepository(AndroidMatchStore(File(context.noBackupFilesDir, "matches-v1.json")), clock)
    val sync = MatchSyncController(repository, AndroidMatchSyncPlatform(context), clock)
    suspend fun initialize() { repository.initialize(); sync.initialize() }
    companion object {
        @Volatile private var instance: AndroidMatchRuntime? = null
        fun get(context: Context): AndroidMatchRuntime = instance ?: synchronized(this) {
            instance ?: AndroidMatchRuntime(context.applicationContext).also { instance = it }
        }
    }
}

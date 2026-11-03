package io.github.szymonbonkowski.apexcompanion

import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.matches.*
import io.github.szymonbonkowski.apexcompanion.ranked.ApexPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Implemented — Not Verified. App-only Foundation/Keychain/URLSession/PhotosPicker bridge. */
interface IosMatchStorageBridge {
    fun readMatchCache(): String?
    fun writeMatchCache(value: String): Boolean
    fun readMatchPairing(): String?
    fun writeMatchPairing(value: String?): Boolean
    fun requestMatchSync(pairing: String, path: String, body: String?, completion: IosHistoryDocumentCompletion)
    fun cancelMatchRequest()
    fun exportMatchDocument(value: String, completion: IosHistoryDocumentCompletion)
    fun importMatchDocument(completion: IosHistoryDocumentCompletion)
    fun cancelMatchDocument()
    fun pickMatchScreenshot(completion: IosHistoryDocumentCompletion)
    fun cancelMatchScreenshot()
}
internal class IosMatchStore(private val bridge: IosMatchStorageBridge): RotationStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) { bridge.readMatchCache() }
    override suspend fun write(value: String) = withContext(Dispatchers.Default) {
        check(value.encodeToByteArray().size <= MATCH_MAX_BYTES && bridge.writeMatchCache(value)) { "Match journal could not be committed" }
    }
}
internal class IosMatchSyncPlatform(private val bridge: IosMatchStorageBridge): MatchSyncPlatform {
    override suspend fun loadPairing(): MatchPairing? = withContext(Dispatchers.Default) {
        bridge.readMatchPairing()?.let(::decodeIosPairing)
    }
    override suspend fun savePairing(value: MatchPairing?) = withContext(Dispatchers.Default) {
        check(bridge.writeMatchPairing(value?.let(::encodeIosPairing))) { "Pairing could not be committed" }
    }
    override suspend fun request(pairing: MatchPairing, path: String, body: String?): String =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancelMatchRequest() }
            bridge.requestMatchSync(encodeIosPairing(pairing), path, body, object: IosHistoryDocumentCompletion {
                override fun complete(value: String?) {
                    if (continuation.isActive) {
                        if (value == null) continuation.resumeWith(Result.failure(IllegalStateException("Local companion unavailable")))
                        else continuation.resume(value)
                    }
                }
            })
        }
}
internal class IosMatchDocuments(private val bridge: IosMatchStorageBridge): HistoryDocuments {
    override suspend fun exportJson(value: String): Boolean = withContext(Dispatchers.Main) {
        require(value.encodeToByteArray().size <= MATCH_MAX_BYTES)
        val result = suspendCancellableCoroutine<String?> { continuation ->
            continuation.invokeOnCancellation { bridge.cancelMatchDocument() }
            bridge.exportMatchDocument(value, object: IosHistoryDocumentCompletion {
                override fun complete(value: String?) { if (continuation.isActive) continuation.resume(value) }
            })
        }
        check(result == null || result == "SAVED"); result == "SAVED"
    }
    override suspend fun importJson(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancelMatchDocument() }
            bridge.importMatchDocument(object: IosHistoryDocumentCompletion {
                override fun complete(value: String?) { if (continuation.isActive) continuation.resume(value) }
            })
        }
    }
}
internal class IosMatchScreenshots(private val bridge: IosMatchStorageBridge): MatchScreenshotPicker {
    override suspend fun pick(): MatchScreenshotDraft? = withContext(Dispatchers.Main) {
        val result = suspendCancellableCoroutine<String?> { continuation ->
            continuation.invokeOnCancellation { bridge.cancelMatchScreenshot() }
            bridge.pickMatchScreenshot(object: IosHistoryDocumentCompletion {
                override fun complete(value: String?) { if (continuation.isActive) continuation.resume(value) }
            })
        } ?: return@withContext null
        require(result.length <= 1024)
        val data = Json.parseToJsonElement(result).jsonObject
        MatchScreenshotDraft(data.getValue("evidenceEventId").jsonPrimitive.content, "manual-screenshot-v1", warning =
            "On-device OCR is not available in this build. Enter only fields you can read from the screenshot, then confirm. No image is retained or uploaded.")
    }
}
private fun encodeIosPairing(p: MatchPairing) = buildJsonObject {
    put("endpoint", p.endpoint); put("certificatePin", p.certificatePin); put("token", p.token); put("companionId", p.companionId)
    put("identity", p.identity?.let { buildJsonObject { put("uid", it.uid); put("platform", it.platform.name) } } ?: JsonNull)
}.toString()
private fun decodeIosPairing(value: String): MatchPairing {
    require(value.encodeToByteArray().size <= 16_384)
    val data = Json.parseToJsonElement(value).jsonObject
    require(data.keys == setOf("endpoint", "certificatePin", "token", "companionId", "identity"))
    val identity = data.getValue("identity").takeIf { it != JsonNull }?.jsonObject?.let {
        require(it.keys == setOf("uid", "platform"))
        HistoryIdentity(it.getValue("uid").jsonPrimitive.content, ApexPlatform.valueOf(it.getValue("platform").jsonPrimitive.content))
    }
    fun text(key: String) = data.getValue(key).jsonPrimitive.also { require(it.isString) }.content
    return MatchPairing(text("endpoint"), text("certificatePin"), text("token"), text("companionId"), identity)
}

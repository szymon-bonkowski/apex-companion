package io.github.szymonbonkowski.apexcompanion.matches

import io.github.szymonbonkowski.apexcompanion.history.HistoryIdentity
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.ApexPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlinx.serialization.json.*

class MatchSyncTest {
    private val now = 1_790_000_000L
    private val identity = HistoryIdentity("700001", ApexPlatform.PC)
    private fun config(identity: HistoryIdentity? = this.identity) = MatchPairing("https://192.168.1.20:45873", "a".repeat(64), "b".repeat(64), "synthetic-pc", identity)
    private class Store: RotationStore {
        var value: String? = null; var fail = false
        override suspend fun read() = value
        override suspend fun write(value: String) { check(!fail); this.value = value }
    }
    private inner class Fixture {
        val store = Store()
        val repository = MatchRepository(store, RotationClock { now })
        val requests = mutableListOf<String>()
        var saved: MatchPairing? = null
        var failAck = false; var failNetwork = false; var unauthorized = false; var permissionGranted = true
        var companion = "synthetic-pc"
        var wireIdentity: HistoryIdentity? = identity
        var records = listOf(record())
        val platform = object: MatchSyncPlatform {
            override suspend fun authorize() = permissionGranted
            override suspend fun loadPairing() = saved
            override suspend fun savePairing(value: MatchPairing?) { saved = value }
            override suspend fun request(pairing: MatchPairing, path: String, body: String?): String {
                requests += path
                check(!failNetwork && !unauthorized)
                if (path == "/v1/ack") {
                    check(!failAck)
                    assertEquals(1L, repository.cursor("synthetic-pc"))
                    assertEquals(1, repository.state.value.records.size)
                    return "{}"
                }
                if (path == "/v1/unpair") return "{}"
                val cursor = path.substringAfter("after=").toLong()
                return batch(companion, if (cursor == 0L && records.isNotEmpty()) 1 else cursor,
                    if (cursor == 0L) records.map { it.copy(identity = wireIdentity) } else emptyList())
            }
        }
        val controller = MatchSyncController(repository, platform, RotationClock { now })
    }
    private fun record() = MatchRecord("match-1", identity = identity, mode = MatchMode.RANKED,
        timestampSeconds = now - 60, timestampSource = MatchTimestampSource.RESULT_OBSERVED,
        source = MatchSource.WINDOWS_CAPTURE, quality = MatchQuality.USER_CONFIRMED,
        evidenceEventId = "synthetic-pc:event-1", parserVersion = "synthetic-v1", kills = 2)
    private fun batch(id: String, cursor: Long, records: List<MatchRecord>): String {
        // Use actual portable record codec; no duplicate production parser in the test.
        val archive = Json.parseToJsonElement(MatchCodec.export(records)).jsonObject
        return buildJsonObject {
            put("schemaVersion", 1); put("companionId", id); put("cursor", cursor)
            put("records", archive.getValue("records"))
        }.toString()
    }
    @Test fun pairingRejectsPublicAddressesMalformedAuthoritiesAndSecrets() {
        listOf("https://8.8.8.8:45873", "http://192.168.1.20:45873", "https://192.168.1.20:443", "https://192.168.1.20:45873/path", "https://010.0.0.1:45873").forEach { endpoint ->
            assertFails { MatchPairing(endpoint, "a".repeat(64), "b".repeat(64), "pc", null) }
        }
        assertFails { MatchPairing("https://10.0.2.2:45873", "x".repeat(64), "b".repeat(64), "pc", null) }
        assertFalse(config().toString().contains("a".repeat(64)))
    }
    @Test fun successfulPairDoesNotInsertBeforeForegroundSyncAndReplayIsIdempotent() = runTest {
        val f = Fixture()
        assertTrue(f.controller.pair(config()))
        assertTrue(f.repository.state.value.records.isEmpty())
        assertTrue(f.controller.synchronize())
        assertEquals(1, f.repository.state.value.records.size)
        assertEquals(1, f.repository.cursor("synthetic-pc"))
        assertTrue(f.controller.synchronize())
        assertEquals(1, f.repository.state.value.records.size)
        assertNotNull(f.controller.state.value.lastSyncSeconds)
        val restartedRepository = MatchRepository(f.store, RotationClock { now })
        val restartedController = MatchSyncController(restartedRepository, f.platform, RotationClock { now })
        restartedController.initialize()
        assertEquals(now, restartedController.state.value.lastSyncSeconds)
    }
    @Test fun offlineReconnectRetainsPairingAndQueue() = runTest {
        val f = Fixture(); assertTrue(f.controller.pair(config()))
        f.failNetwork = true
        assertFalse(f.controller.synchronize())
        assertNotNull(f.saved); assertEquals(0, f.repository.cursor("synthetic-pc"))
        f.failNetwork = false
        assertTrue(f.controller.synchronize()); assertEquals(1, f.repository.state.value.records.size)
    }
    @Test fun failedAcknowledgementCannotUndoCommittedBatchOrCreateDuplicate() = runTest {
        val f = Fixture(); assertTrue(f.controller.pair(config()))
        f.failAck = true
        assertFalse(f.controller.synchronize())
        assertEquals(1, f.repository.state.value.records.size)
        val restarted = MatchRepository(f.store, RotationClock { now }); restarted.initialize()
        assertEquals(1, restarted.cursor("synthetic-pc"))
        f.failAck = false
        assertTrue(f.controller.synchronize()); assertEquals(1, f.repository.state.value.records.size)
    }
    @Test fun failedStorageCannotAcknowledgeOrAdvanceCursor() = runTest {
        val f = Fixture(); assertTrue(f.controller.pair(config()))
        f.store.fail = true
        assertFalse(f.controller.synchronize())
        assertEquals(0, f.repository.cursor("synthetic-pc"))
        assertFalse(f.requests.contains("/v1/ack")); assertTrue(f.repository.state.value.records.isEmpty())
    }
    @Test fun foreignIdentityAndCompanionCannotPair() = runTest {
        val f = Fixture(); f.wireIdentity = HistoryIdentity("800002", ApexPlatform.PC)
        assertFalse(f.controller.pair(config())); assertNull(f.saved)
        f.wireIdentity = identity; f.companion = "other-pc"
        assertFalse(f.controller.pair(config())); assertNull(f.saved)
    }
    @Test fun deniedLocalNetworkPermissionCannotMakeRequestOrPersistPairing() = runTest {
        val f = Fixture(); f.permissionGranted = false
        assertFalse(f.controller.pair(config())); assertNull(f.saved); assertTrue(f.requests.isEmpty())
    }
    @Test fun unauthorizedCannotPairOrSaveSecrets() = runTest {
        val f = Fixture(); f.unauthorized = true
        assertFalse(f.controller.pair(config())); assertNull(f.saved)
        assertTrue(f.repository.state.value.records.isEmpty())
    }
    @Test fun offlineUnpairForgetsLocalSecretButRequiresPcRevocation() = runTest {
        val f = Fixture(); assertTrue(f.controller.pair(config()))
        f.failNetwork = true
        assertTrue(f.controller.unpair()); assertNull(f.saved)
        assertFalse(f.controller.state.value.paired)
        assertTrue(f.controller.state.value.error!!.contains("revoke"))
    }
    @Test fun pairedIdentityCannotChangeDuringSync() = runTest {
        val f = Fixture(); assertTrue(f.controller.pair(config()))
        f.wireIdentity = null
        assertFalse(f.controller.synchronize()); assertTrue(f.repository.state.value.records.isEmpty())
        assertEquals(0, f.repository.cursor("synthetic-pc"))
    }
}

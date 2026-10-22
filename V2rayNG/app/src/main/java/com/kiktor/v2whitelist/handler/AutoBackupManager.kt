package com.kiktor.v2whitelist.handler

import android.content.Context
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.util.JsonUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Maintains a small pool of profiles that have passed repeated real traffic checks. */
object AutoBackupManager {
    const val MAX_BACKUPS = 3

    private const val MIN_CONFIRMATION_GAP_MS = 20_000L
    private const val MAX_ACTIVE_CHECK_GAP_MS = 10 * 60_000L
    private const val BACKUP_FRESHNESS_MS = 24 * 60 * 60_000L
    private const val BACKUP_RECHECK_INTERVAL_MS = 20_000L
    private const val PROFILE_CHECK_TIMEOUT_MS = 3_000
    private const val MAX_SCAN_CHUNKS_PER_REFRESH = 4
    private const val MAX_CANDIDATE_PROFILES_PER_REFRESH = 5
    private const val KEY = AppConfig.PREF_AUTO_BACKUP_PROFILES

    private data class BackupEntry(
        val guid: String = "",
        val server: String? = null,
        val serverPort: String? = null,
        val remarks: String? = null,
        val successfulChecks: Int = 0,
        val lastVerifiedAt: Long = 0L,
        val lastDelayMillis: Long = Long.MAX_VALUE,
        val lastFailureAt: Long = 0L
    )

    private data class ActiveCheckProgress(val count: Int, val lastSuccessAt: Long)

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshInProgress = AtomicBoolean(false)
    private val activeCheckProgress = mutableMapOf<String, ActiveCheckProgress>()
    private var refreshJob: Job? = null

    /** Returns verified backup profiles in reliability order, optionally excluding stale checks. */
    @Synchronized
    fun getBackupGuids(currentGuid: String? = null, requireFresh: Boolean = true): List<String> {
        val now = System.currentTimeMillis()
        val entries = readAndRemapEntries()
        val eligible = entries.filter { entry ->
            entry.successfulChecks >= 2 &&
                (!requireFresh || now - entry.lastVerifiedAt <= BACKUP_FRESHNESS_MS)
        }
        if (eligible.isEmpty()) return emptyList()

        val filteredGuids = SmartConnectManager.filterServers(
            eligible.map { it.guid },
            excludeGuid = currentGuid,
            filterByScenario = true,
            excludeQuarantined = true
        ).map { it.first }.toSet()

        return eligible.asSequence()
            .filter { it.guid in filteredGuids }
            .sortedWith(backupOrder())
            .take(MAX_BACKUPS)
            .map { it.guid }
            .toList()
    }

    /** A healthy live tunnel becomes a reserve after two heartbeat checks. */
    @Synchronized
    fun recordActiveHeartbeatSuccess(guid: String?, delayMillis: Long) {
        if (guid.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val previous = activeCheckProgress[guid]
        val next = when {
            previous == null -> ActiveCheckProgress(1, now)
            now - previous.lastSuccessAt < MIN_CONFIRMATION_GAP_MS -> previous
            now - previous.lastSuccessAt <= MAX_ACTIVE_CHECK_GAP_MS ->
                ActiveCheckProgress((previous.count + 1).coerceAtMost(10), now)
            else -> ActiveCheckProgress(1, now)
        }
        activeCheckProgress[guid] = next

        if (next.count >= 2) {
            val existing = readAndRemapEntries().firstOrNull { it.guid == guid }
            if (existing == null || now - existing.lastVerifiedAt >= 5 * 60_000L) {
                rememberVerifiedProfile(guid, next.count, delayMillis)
            }
        }
    }

    /** Refreshes the verification time for a profile that was already in the reserve. */
    @Synchronized
    fun recordBackupCheckSuccess(guid: String, delayMillis: Long) {
        val entries = readAndRemapEntries()
        val existing = entries.firstOrNull { it.guid == guid } ?: return
        if (existing.successfulChecks < 2) return
        upsert(
            entries,
            entryFor(guid, existing.successfulChecks + 1, delayMillis, existing)
        )
    }

    /** A failed live check makes the profile ineligible until it passes verification again. */
    @Synchronized
    fun recordBackupCheckFailure(guid: String) {
        activeCheckProgress.remove(guid)
        val entries = readAndRemapEntries()
        val existing = entries.firstOrNull { it.guid == guid } ?: return
        upsert(
            entries,
            existing.copy(successfulChecks = 0, lastVerifiedAt = 0L, lastFailureAt = System.currentTimeMillis())
        )
    }

    @Synchronized
    fun rememberVerifiedProfile(guid: String, successfulChecks: Int = 2, delayMillis: Long = Long.MAX_VALUE) {
        if (guid.isBlank() || successfulChecks < 2) return
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        val entries = readAndRemapEntries()
        upsert(entries, entryFor(guid, successfulChecks, delayMillis, entries.firstOrNull { it.guid == guid }, profile))
        activeCheckProgress.remove(guid)
    }

    /** Starts bounded background discovery; only profiles passing two real HTTP checks are saved. */
    fun scheduleRefresh(context: Context) {
        if (!refreshInProgress.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val job = backgroundScope.launch(start = CoroutineStart.LAZY) {
            try {
                recheckStaleBackups(appContext)
                fillBackupPool(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GeekModeLogger.log("AutoBackup", "Background reserve refresh failed: ${e.message}")
            } finally {
                val completedJob = coroutineContext[Job]
                synchronized(this@AutoBackupManager) {
                    if (refreshJob === completedJob) {
                        refreshJob = null
                        refreshInProgress.set(false)
                    }
                }
            }
        }
        synchronized(this) {
            refreshJob = job
        }
        job.start()
    }

    @Synchronized
    fun cancelRefresh() {
        val job = refreshJob
        refreshJob = null
        refreshInProgress.set(false)
        job?.cancel()
    }

    @Synchronized
    private fun readAndRemapEntries(): MutableList<BackupEntry> {
        val raw = MmkvManager.decodeSettingsString(KEY)
        if (raw.isNullOrBlank()) return mutableListOf()

        val saved = try {
            JsonUtil.fromJson(raw, Array<BackupEntry>::class.java)?.toMutableList() ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
        if (saved.isEmpty()) {
            MmkvManager.encodeSettings(KEY, null)
            return mutableListOf()
        }

        val currentProfiles = MmkvManager.decodeServerList().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { guid to it }
        }
        val byGuid = currentProfiles.toMap()
        val byIdentity = currentProfiles.associateBy { identity(it.second) }
        val remapped = saved.mapNotNull { entry ->
            val current = byGuid[entry.guid]?.let { entry.guid to it }
                ?: byIdentity[identity(entry.server, entry.serverPort, entry.remarks)]
                ?: return@mapNotNull null
            entry.copy(
                guid = current.first,
                server = current.second.server,
                serverPort = current.second.serverPort,
                remarks = current.second.remarks
            )
        }.distinctBy { it.guid }.toMutableList()

        if (remapped != saved) saveEntries(remapped)
        return remapped
    }

    private suspend fun recheckStaleBackups(context: Context) {
        val currentGuid = MmkvManager.getSelectServer()
        val staleGuids = getBackupGuids(currentGuid, requireFresh = false).filter { guid ->
            val entry = readAndRemapEntries().firstOrNull { it.guid == guid }
            entry != null && System.currentTimeMillis() - entry.lastVerifiedAt > BACKUP_FRESHNESS_MS
        }

        for (guid in staleGuids) {
            if (!coroutineContext.isActive) return
            val delayMillis = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: Long.MAX_VALUE
            val verified = NodeTesterManager.verifyProfile(
                context,
                guid,
                showStatus = false,
                customTimeoutMs = PROFILE_CHECK_TIMEOUT_MS,
                skipSpeedCheck = true
            )
            if (verified) {
                recordBackupCheckSuccess(guid, delayMillis)
            } else {
                recordBackupCheckFailure(guid)
                MmkvManager.quarantineProfileForAutoFailover(guid)
                GeekModeLogger.log("AutoBackup", "Stale reserve profile failed recheck and was removed from service: $guid")
            }
        }
    }

    private suspend fun fillBackupPool(context: Context) {
        val attemptedGuids = mutableSetOf<String>()
        var attemptedCandidates = 0
        val chunks = ChunkingHelper.buildProportionalChunks(
            SmartConnectManager.filterServers(MmkvManager.decodeServerList())
        ).take(MAX_SCAN_CHUNKS_PER_REFRESH)

        for (chunk in chunks) {
            val currentGuid = MmkvManager.getSelectServer()
            if (!coroutineContext.isActive || getBackupGuids(currentGuid, requireFresh = true).size >= MAX_BACKUPS) return
            val existingReady = getBackupGuids(currentGuid, requireFresh = true).toSet()
            val scanResults = NodeTesterManager.testServers(context, chunk)

            for ((guid, _, delayMillis) in scanResults) {
                val activeGuid = MmkvManager.getSelectServer()
                if (!coroutineContext.isActive || getBackupGuids(activeGuid, requireFresh = true).size >= MAX_BACKUPS) return
                if (delayMillis >= Long.MAX_VALUE) continue
                if (guid == MmkvManager.getSelectServer() || guid in existingReady || !attemptedGuids.add(guid)) continue
                if (MmkvManager.getActiveFailoverQuarantines().containsKey(guid)) continue
                if (attemptedCandidates >= MAX_CANDIDATE_PROFILES_PER_REFRESH) return
                attemptedCandidates++

                val firstCheck = verifyForReserve(context, guid)
                if (!firstCheck) continue
                delay(BACKUP_RECHECK_INTERVAL_MS)
                if (!coroutineContext.isActive || guid == MmkvManager.getSelectServer()) continue

                if (verifyForReserve(context, guid)) {
                    rememberVerifiedProfile(guid, successfulChecks = 2, delayMillis = delayMillis)
                    GeekModeLogger.log("AutoBackup", "Added verified reserve profile $guid (${getBackupGuids(activeGuid).size}/$MAX_BACKUPS)")
                } else {
                    GeekModeLogger.log("AutoBackup", "Candidate did not pass its second reserve check: $guid")
                }
            }
        }
    }

    private suspend fun verifyForReserve(context: Context, guid: String): Boolean =
        NodeTesterManager.verifyProfile(
            context,
            guid,
            showStatus = false,
            customTimeoutMs = PROFILE_CHECK_TIMEOUT_MS,
            skipSpeedCheck = true
        )

    @Synchronized
    private fun upsert(entries: MutableList<BackupEntry>, entry: BackupEntry) {
        entries.removeAll { it.guid == entry.guid }
        entries.add(entry)
        val currentGuid = MmkvManager.getSelectServer()
        val protectedActive = entries.firstOrNull { it.guid == currentGuid }
        val otherProfiles = entries.asSequence()
            .filterNot { it.guid == currentGuid }
            .sortedWith(backupOrder())
            .take(MAX_BACKUPS)
            .toList()
        saveEntries((listOfNotNull(protectedActive) + otherProfiles).distinctBy { it.guid })
    }

    private fun saveEntries(entries: List<BackupEntry>) {
        if (entries.isEmpty()) {
            MmkvManager.encodeSettings(KEY, null)
        } else {
            MmkvManager.encodeSettings(KEY, JsonUtil.toJson(entries))
        }
    }

    private fun entryFor(
        guid: String,
        successfulChecks: Int,
        delayMillis: Long,
        previous: BackupEntry? = null,
        profile: ProfileItem? = MmkvManager.decodeServerConfig(guid)
    ): BackupEntry {
        val item = profile ?: return previous?.copy(guid = guid, successfulChecks = successfulChecks)
            ?: BackupEntry(guid = guid, successfulChecks = successfulChecks, lastVerifiedAt = System.currentTimeMillis())
        return BackupEntry(
            guid = guid,
            server = item.server,
            serverPort = item.serverPort,
            remarks = item.remarks,
            successfulChecks = successfulChecks.coerceAtMost(10),
            lastVerifiedAt = System.currentTimeMillis(),
            lastDelayMillis = delayMillis.takeIf { it > 0L } ?: previous?.lastDelayMillis ?: Long.MAX_VALUE,
            lastFailureAt = previous?.lastFailureAt ?: 0L
        )
    }

    private fun identity(profile: ProfileItem): String =
        identity(profile.server, profile.serverPort, profile.remarks)

    private fun identity(server: String?, port: String?, remarks: String?): String =
        listOf(server.orEmpty(), port.orEmpty(), remarks.orEmpty()).joinToString("\u001f")

    private fun backupOrder(): Comparator<BackupEntry> =
        compareByDescending<BackupEntry> { it.successfulChecks }
            .thenByDescending { it.lastVerifiedAt }
            .thenBy { it.lastDelayMillis }
}

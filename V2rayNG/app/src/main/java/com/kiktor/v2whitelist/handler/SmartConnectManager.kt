package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.handler.GeekModeLogger
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.dto.SubscriptionItem
import com.kiktor.v2whitelist.dto.SubscriptionCache
import com.kiktor.v2whitelist.enums.EConfigType
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.AngConfigManager
import com.kiktor.v2whitelist.handler.V2rayConfigManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.ui.LocationFilterActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController

object SmartConnectManager {
    val isScanning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sendStatus(context: Context, status: String) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, status)
        // Если идёт поиск — обновляем текст уведомления (беззвучно)
        if (isScanning.get()) {
            NotificationManager.updateScanningNotification(context, status)
        }
    }


    private fun loadCustomSubs(): List<CustomSubData> {
        val json = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            com.kiktor.v2whitelist.util.JsonUtil.fromJson(json, Array<CustomSubData>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Дата-класс для JSON-десериализации кастомных подписок */
    data class CustomSubData(
        val id: String = "",
        val name: String = "",
        val url: String = "",
        val filter: String = "",
        val groupRegex: String = "",
        val enabled: Boolean = true,
        val sharePercent: Int? = null,
        val catalogSourceIds: List<String> = emptyList()
    )


    fun filterServerPairs(
        candidates: List<Pair<String, ProfileItem>>, 
        excludeGuid: String? = null,
        filterByScenario: Boolean = true,
        excludeQuarantined: Boolean = true
    ): List<Pair<String, ProfileItem>> {
        val enabledCatalogSourceIds = SubscriptionSourcesManager.enabledCatalogSourceIds()
        val builtinSourceEnabled = SubscriptionSourcesManager.isBuiltinSourceEnabled()
        val disabledSubIds = loadCustomSubs().filter {
            !it.enabled || !SubscriptionSourcesManager.isSubscriptionSourceEnabled(
                it.id, it.catalogSourceIds, enabledCatalogSourceIds, builtinSourceEnabled
            )
        }.map { "custom_sub_${it.id}" }.toSet()
        val quarantinedGuids = if (excludeQuarantined) MmkvManager.getActiveFailoverQuarantines().keys else emptySet()
        
        val filterMode = MmkvManager.decodeSettingsString(
            AppConfig.PREF_LOCATION_FILTER_MODE,
            AppConfig.LOCATION_FILTER_MODE_EXCLUDE
        ) ?: AppConfig.LOCATION_FILTER_MODE_EXCLUDE

        val filterSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_LOCATION_FILTER_SET)
            ?: com.kiktor.v2whitelist.ui.LocationFilterActivity.getDefaultFilterSet()
            
        val groupRegexMap = com.kiktor.v2whitelist.ui.LocationFilterActivity.getGroupRegexMap()
        val filterInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_FILTER_INSECURE_PROFILES, false)

        val basicFiltered = candidates.filter { (guid, profile) ->
            if (excludeGuid != null && guid == excludeGuid) return@filter false
            if (quarantinedGuids.contains(guid)) return@filter false
            if (disabledSubIds.contains(profile.subscriptionId)) return@filter false
            if (filterInsecure && !profile.isSecure()) return@filter false
            if (profile.configType == com.kiktor.v2whitelist.enums.EConfigType.POLICYGROUP) return@filter false
            
            if (filterSet.isEmpty()) return@filter true
            
            val regexStr = groupRegexMap[profile.subscriptionId]
            val tag = com.kiktor.v2whitelist.ui.LocationFilterActivity.resolveServerTag(profile.remarks, regexStr)
            
            when (filterMode) {
                AppConfig.LOCATION_FILTER_MODE_EXCLUDE -> {
                    !filterSet.contains(tag)
                }
                AppConfig.LOCATION_FILTER_MODE_WHITELIST -> {
                    filterSet.contains(tag)
                }
                else -> true
            }
        }

        if (!filterByScenario || basicFiltered.isEmpty()) {
            return basicFiltered
        }

        val currentScenario = MmkvManager.decodeSettingsString(
            AppConfig.PREF_CURRENT_APP_SCENARIO, 
            SubscriptionHelper.AppScenario.AUTO.name
        ) ?: SubscriptionHelper.AppScenario.AUTO.name

        val targetScenarios: Set<String>? = when (currentScenario) {
            SubscriptionHelper.AppScenario.AUTO.name -> {
                val internetStatus = NetworkManager.checkInternetStatus()
                if (internetStatus == 1) { // JAMMED
                    setOf(SubscriptionHelper.SCENARIO_WHITELIST)
                } else { // 0 (OK) или 2 (нет сети)
                    setOf(SubscriptionHelper.SCENARIO_BLACKLIST, SubscriptionHelper.SCENARIO_YOUTUBE)
                }
            }
            SubscriptionHelper.AppScenario.WHITELIST.name -> setOf(SubscriptionHelper.SCENARIO_WHITELIST)
            SubscriptionHelper.AppScenario.VPN_BLACKLIST.name -> setOf(SubscriptionHelper.SCENARIO_BLACKLIST)
            SubscriptionHelper.AppScenario.YOUTUBE.name -> setOf(SubscriptionHelper.SCENARIO_YOUTUBE)
            else -> null // KEEP_CURRENT или неизвестно: без дополнительной фильтрации
        }

        if (targetScenarios == null) {
            return basicFiltered
        }

        val subScenariosMap = SubscriptionHelper.getSubScenariosMap()
        val scenarioFiltered = basicFiltered.filter { (_, profile) ->
            val subScenarios = subScenariosMap[profile.subscriptionId]
            // Если подписка не имеет тегов — считаем ее универсальной
            subScenarios.isNullOrEmpty() || subScenarios.any { targetScenarios.contains(it) }
        }

        return if (scenarioFiltered.isNotEmpty()) {
            scenarioFiltered
        } else {
            // Graceful Fallback: если у пользователя нет серверов в целевом пуле (например, добавлены только БС или только ЧС),
            // возвращаем все доступные базовые серверы, чтобы связь не прерывалась!
            GeekModeLogger.log("SmartConnect", "Scenario filtering: 0 servers matched $targetScenarios. Fallback to all ${basicFiltered.size} enabled servers")
            basicFiltered
        }
    }

    fun filterServers(
        allServers: List<String>,
        excludeGuid: String? = null,
        filterByScenario: Boolean = true,
        excludeQuarantined: Boolean = true
    ): List<Pair<String, ProfileItem>> {
        val candidates = allServers.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile != null) guid to profile else null
        }
        return filterServerPairs(candidates, excludeGuid, filterByScenario, excludeQuarantined)
    }

    fun clearSelectedServerIfFilteredOut() {
        val allServers = MmkvManager.decodeServerList()
        val validFilteredGuids = filterServers(
            allServers,
            filterByScenario = false,
            excludeQuarantined = false
        ).map { it.first }.toSet()
        val currentSelected = MmkvManager.getSelectServer()
        if (currentSelected != null && currentSelected.isNotEmpty() && !validFilteredGuids.contains(currentSelected)) {
            MmkvManager.setSelectServer("")
        }
    }

    const val SUBSCRIPTION_ID = "v2whitelist_hardcoded_sub"
    const val UPDATE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour



    private suspend fun connectToBest(context: Context, best: Triple<String, ProfileItem, Long>, isStartup: Boolean = false) {
        GeekModeLogger.log("SmartConnect", "Smart Connect: Selected ${best.second.remarks} (${best.third}ms)")
        sendStatus(context, context.getString(R.string.status_connecting_to, best.second.remarks))
        MmkvManager.setSelectServer(best.first)

        MmkvManager.saveLastConnectedServer(best.first)

        val isRunning = V2RayServiceManager.isRunning()
        if (isRunning) {
            MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_SWITCH_SERVER, "")
        } else {
            withContext(Dispatchers.Main) {
                V2RayServiceManager.startVService(context.applicationContext)
            }
        }
        
        if (isStartup) {
            val internetStatus = NetworkManager.checkInternetStatus()
            if (internetStatus == 1) { // JAMMED
                // ВАЖНО: используем GlobalScope.launch, а НЕ coroutineScope!
                // coroutineScope блокировал возврат из connectToBest на 5+ секунд,
                // и если updateSubscription падал — убивал весь smartConnect → серая кнопка.
                CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                    try {
                        delay(5000) // Ждем пока VPN разгонится
                        GeekModeLogger.log("SmartConnect", "Survival logic: Jamming detected, triggering background update via VPN")
                        SubscriptionHelper.updateSubscription(context, sequential = false)
                    } catch (e: Exception) {
                        GeekModeLogger.log("SmartConnect", "Survival logic: background update failed" + ": " + e)
                    }
                }
            }

            val isAutoUpdateEnabled = MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE, true)
            if (isAutoUpdateEnabled) {
                // Ищем самую старую подписку среди включенных (или 0, если еще не обновляли)
                val allSubs = MmkvManager.decodeSubscriptions()
                val oldestUpdate = allSubs.filter {
                    it.subscription.enabled && it.guid != AppConfig.LOCAL_PROFILES_SUBSCRIPTION_GUID
                }
                    .minOfOrNull { it.subscription.lastUpdated } ?: 0L

                if (System.currentTimeMillis() - oldestUpdate > UPDATE_INTERVAL_MS) {
                    GeekModeLogger.log("SmartConnect", "smartConnect: triggering background subscription update")
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            // Быстрое параллельное обновление подписок с mirror racing
                            SubscriptionHelper.updateSubscription(context, isStartup = true, sequential = false)
                        } catch (e: Exception) {
                            GeekModeLogger.log("SmartConnect", "smartConnect: background update failed" + ": " + e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun connectFromAutoBackup(
        context: Context,
        isStartup: Boolean,
        excludeGuid: String? = null
    ): Boolean {
        val backupGuids = AutoBackupManager.getBackupGuids(excludeGuid, requireFresh = false)
        if (backupGuids.isEmpty()) return false

        GeekModeLogger.log("AutoBackup", "Checking ${backupGuids.size} saved reserve profile(s)")
        for (guid in backupGuids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            sendStatus(context, context.getString(R.string.status_testing_servers))
            val verified = NodeTesterManager.verifyProfile(
                context,
                guid,
                showStatus = false,
                customTimeoutMs = 3_000,
                skipSpeedCheck = true
            )
            if (verified) {
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: Long.MAX_VALUE
                AutoBackupManager.recordBackupCheckSuccess(guid, delay)
                GeekModeLogger.log("AutoBackup", "Reserve profile passed startup check: ${profile.remarks} ($guid)")
                connectToBest(context, Triple(guid, profile, delay), isStartup)
                return true
            }

            AutoBackupManager.recordBackupCheckFailure(guid)
            MmkvManager.quarantineProfileForAutoFailover(guid)
            GeekModeLogger.log("AutoBackup", "Reserve profile failed check and was quarantined: ${profile.remarks} ($guid)")
        }
        return false
    }

    /**
     * Logic for "Smart Connect" - filter, sort by RealPing, and connect to best.
     */
    suspend fun smartConnect(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isScanning.compareAndSet(false, true)) {
            GeekModeLogger.log("SmartConnect", "smartConnect: already scanning, ignoring duplicate call")
            return@withContext false
        }
        NotificationManager.showScanningNotification(context)
        try {
            // Ждем появления интернета перед проверкой доступных серверов
            NetworkManager.waitForInternet(context)


            // Проверяем состояние интернета и запоминаем — чтобы не перезаписать в цикле
            val internetStatus = NetworkManager.checkInternetStatus()
            when (internetStatus) {
                0 -> {
                    GeekModeLogger.log("SmartConnect", "SmartConnect: internet is available")
                    sendStatus(context, context.getString(R.string.status_testing_servers))
                }
                1 -> {
                    GeekModeLogger.log("SmartConnect", "SmartConnect: internet is jammed (only local resources available)")
                    sendStatus(context, context.getString(R.string.status_jamming_detected))
                }
                else -> {
                    GeekModeLogger.log("SmartConnect", "SmartConnect: no internet connection")
                    sendStatus(context, context.getString(R.string.status_no_internet))
                }
            }

            SubscriptionHelper.checkAndSetupSubscription(context)

            if (connectFromAutoBackup(context, isStartup = true)) {
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            val allServers = MmkvManager.decodeServerList()
            val filteredServers = filterServers(allServers).shuffled()

            if (filteredServers.isEmpty()) {
                GeekModeLogger.log("SmartConnect", "No servers found in hardcoded subscription")
                sendStatus(context, context.getString(R.string.status_no_servers))
                return@withContext false
            }

            val strictExitChecks = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, false) ||
                ServiceAppsRoutingManager.requiresNonRussianExit()
            if (MmkvManager.isV2wCoreEnabled() && !strictExitChecks) {
                val v2wSuccess = V2WScannerEngine.runV2WCoreScan(
                    context, 
                    filteredServers, 
                    isStartup = true,
                    internetStatus = internetStatus,
                    sendStatus = { status -> sendStatus(context, status) },
                    connectToBest = { candidate, startup -> connectToBest(context, Triple(candidate.first, candidate.second, 0L), startup) }
                )
                if (v2wSuccess) {
                    return@withContext true
                }
                if (!MmkvManager.isV2wFallbackEnabled()) {
                    return@withContext false
                }
                GeekModeLogger.log("SmartConnect", "v2w-core found no working servers, falling back to classic scanner")
                sendStatus(context, context.getString(R.string.status_v2w_fallback_to_classic))
                delay(1000)
            }

            val chunkedServers = buildProportionalChunks(filteredServers)
            var best: Triple<String, ProfileItem, Long>? = null
            val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true) || strictExitChecks

            for ((index, chunk) in chunkedServers.withIndex()) {
                GeekModeLogger.log("SmartConnect", "Starting Smart Connect for chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
                // Обновляем статус "тестируем" только если интернет в норме.
                // При "глушат" (1) и "нет интернета" (2) строки уже говорят "ищём серверы" —
                // перезаписывать их бессмысленно, иначе пользователь не увидит важный контекст.
                if (internetStatus == 0) {
                    sendStatus(context, context.getString(R.string.status_testing_servers))
                }

                val results = NodeTesterManager.testServers(context, chunk)

                if (profileCheckEnabled) {
                    for (candidate in results.filter { it.third < Long.MAX_VALUE }) {
                        if (NodeTesterManager.verifyProfile(context, candidate.first, showStatus = (internetStatus == 0))) {
                            best = candidate
                            break
                        } else {
                            if (internetStatus == 0) {
                                sendStatus(context, context.getString(R.string.status_profile_check_failed))
                            }
                        }
                    }
                } else {
                    best = results.firstOrNull { it.third < Long.MAX_VALUE }
                }

                if (best != null) {
                    break // Found a working server, stop testing other chunks
                }
                GeekModeLogger.log("SmartConnect", "No working server found in chunk ${index + 1}, moving to next chunk...")
            }

            if (best != null) {
                connectToBest(context, best, isStartup = true)
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            val customEndpointEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL)
            if (customEndpointEnabled && !endpointUrl.isNullOrBlank() && filteredServers.isNotEmpty()) {
                val testCandidate = filteredServers.first()
                val worksDirectly = NodeTesterManager.verifyProfile(context, testCandidate.first, showStatus = false, ignoreCustomEndpoint = true)
                if (worksDirectly) {
                    val endpointProfile = AngConfigManager.identifyConfigType(endpointUrl)
                    val endpointName = endpointProfile?.remarks?.takeIf { it.isNotBlank() } ?: "Конечный узел"
                    GeekModeLogger.log("SmartConnect", "⚠️ Servers work directly, but Custom Endpoint ($endpointName) is DOWN!")
                    sendStatus(context, "⚠️ Конечный узел ($endpointName) не отвечает")
                    return@withContext false
                }
            }

            GeekModeLogger.log("SmartConnect", "No working server found after checking all chunks")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext false
        } finally {
            isScanning.set(false)
            NotificationManager.cancelScanningNotification(context)
        }
    }

    /**
     * Switches to the next best server.
     */
    suspend fun switchServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isScanning.compareAndSet(false, true)) {
            GeekModeLogger.log("SmartConnect", "switchServer: already scanning, ignoring duplicate call")
            return@withContext false
        }
        NotificationManager.showScanningNotification(context, context.getString(R.string.status_switching_server))
        try {
            // Ждем появления интернета (dzen.ru) перед переключением
            NetworkManager.waitForInternet(context)
            
            val currentGuid = MmkvManager.getSelectServer()

            if (connectFromAutoBackup(context, isStartup = false, excludeGuid = currentGuid)) {
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }
            
            val allServers = MmkvManager.decodeServerList()
            val filteredServers = filterServers(allServers, excludeGuid = currentGuid).shuffled()

            if (filteredServers.isEmpty()) {
                return@withContext false
            }

            val strictExitChecks = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, false) ||
                ServiceAppsRoutingManager.requiresNonRussianExit()
            if (MmkvManager.isV2wCoreEnabled() && !strictExitChecks) {
                val v2wSuccess = V2WScannerEngine.runV2WCoreScan(
                    context, 
                    filteredServers, 
                    isStartup = false,
                    sendStatus = { status -> sendStatus(context, status) },
                    connectToBest = { candidate, startup -> connectToBest(context, Triple(candidate.first, candidate.second, 0L), startup) }
                )
                if (v2wSuccess) {
                    return@withContext true
                }
                if (!MmkvManager.isV2wFallbackEnabled()) {
                    return@withContext false
                }
                GeekModeLogger.log("SmartConnect", "v2w-core found no working servers in switchToNextServer, falling back to classic scanner")
                sendStatus(context, context.getString(R.string.status_v2w_fallback_to_classic))
                delay(1000)
            }

            sendStatus(context, context.getString(R.string.status_switching_server))

            val chunkedServers = buildProportionalChunks(filteredServers)
            var nextBest: Triple<String, ProfileItem, Long>? = null
            val profileCheckEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROFILE_CHECK_ENABLED, true) || strictExitChecks

            for ((index, chunk) in chunkedServers.withIndex()) {
                GeekModeLogger.log("SmartConnect", "Switching server: testing chunk ${index + 1}/${chunkedServers.size} (${chunk.size} servers)")
                sendStatus(context, context.getString(R.string.status_testing_servers))

                val results = NodeTesterManager.testServers(context, chunk)

                if (profileCheckEnabled) {
                    for (candidate in results.filter { it.third < Long.MAX_VALUE }) {
                        if (NodeTesterManager.verifyProfile(context, candidate.first)) {
                            nextBest = candidate
                            break
                        }
                    }
                } else {
                    nextBest = results.firstOrNull { it.third < Long.MAX_VALUE }
                }

                if (nextBest != null) {
                    break
                }
                GeekModeLogger.log("SmartConnect", "No working server found in chunk ${index + 1}, moving to next chunk...")
            }

            if (nextBest != null) {
                connectToBest(context, nextBest, isStartup = false)
                NotificationManager.cancelFailoverNotification()
                return@withContext true
            }

            val customEndpointEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_CUSTOM_ENDPOINT_ENABLED, false)
            val endpointUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_ENDPOINT_URL)
            if (customEndpointEnabled && !endpointUrl.isNullOrBlank() && filteredServers.isNotEmpty()) {
                val testCandidate = filteredServers.first()
                val worksDirectly = NodeTesterManager.verifyProfile(context, testCandidate.first, showStatus = false, ignoreCustomEndpoint = true)
                if (worksDirectly) {
                    val endpointProfile = AngConfigManager.identifyConfigType(endpointUrl)
                    val endpointName = endpointProfile?.remarks?.takeIf { it.isNotBlank() } ?: "Конечный узел"
                    GeekModeLogger.log("SmartConnect", "switchServer: ⚠️ Servers work directly, but Custom Endpoint ($endpointName) is DOWN!")
                    sendStatus(context, "⚠️ Конечный узел ($endpointName) не отвечает")
                    return@withContext false
                }
            }

            GeekModeLogger.log("SmartConnect", "switchServer: No working server found")
            sendStatus(context, context.getString(R.string.status_no_servers))
            return@withContext false
        } finally {
            isScanning.set(false)
            NotificationManager.cancelScanningNotification(context)
        }
    }

    /**
     * Надежно проверяет, работает ли прокси на локальном порту (межпроцессная проверка)
     */
    fun isProxyRunning(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Splits filtered servers into chunks based on proportional logic.
     */
    private fun buildProportionalChunks(servers: List<Pair<String, ProfileItem>>): List<List<Pair<String, ProfileItem>>> {
        return ChunkingHelper.buildProportionalChunks(servers)
    }


}

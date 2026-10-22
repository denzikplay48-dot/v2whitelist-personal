package com.kiktor.v2whitelist.handler

import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Умный Auto-Failover с пассивным отслеживанием аномалий трафика.
 *
 * Режимы работы:
 * - Heartbeat: периодическая HTTP-проверка туннеля (каждые N секунд)
 * - Smart Stall Detection: пассивный мониторинг трафика раз в секунду через [onTrafficSample]
 *   Если есть исходящий трафик (> 1.5 KB/s) но входящий упал ниже порога → экспресс-проверка → failover
 *
 * После автоматического переключения не возвращается к отказавшему профилю в течение заданного карантина.
 */
object SmartFailoverManager {

    private const val FALLBACK_TUNNEL_PROBE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    private data class TunnelHealth(
        val delayMillis: Long,
        val maxAcceptedDelayMillis: Long,
        val exitIpAvailable: Boolean,
        val exitIpCountry: String?,
        val requireNonRussianExit: Boolean,
        val fallbackEndpointAvailable: Boolean? = null
    ) {
        val delayProbePassed: Boolean
            get() = delayMillis in 1L..maxAcceptedDelayMillis

        val exitIpAccepted: Boolean
            get() = exitIpAvailable && (!requireNonRussianExit || ServiceAppsRoutingManager.isKnownNonRussianCountry(exitIpCountry))

        val isHealthy: Boolean
            get() = delayProbePassed && exitIpAccepted

        fun summary(): String = "ping=$delayProbePassed(${delayMillis}ms/$maxAcceptedDelayMillis), exitIp=$exitIpAvailable, country=${exitIpCountry ?: "unknown"}, countryAccepted=$exitIpAccepted, fallback=$fallbackEndpointAvailable"
    }

    enum class PhysicalNetworkState {
        NO_INTERNET,      // Физического интернета нет совсем (лифт, метро, режим полета)
        WHITELIST_ONLY,   // ya.ru доступен, google.com недоступен (белые списки оператора)
        FULL_INTERNET     // ya.ru и google.com доступны (полноценный интернет)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    private val isProbing = AtomicBoolean(false)
    private val quickProbeOnNextStart = AtomicBoolean(false)
    private var consecutiveStallSeconds = 0

    @Volatile
    private var isMonitoring = false

    /**
     * Запускает фоновый мониторинг доступности сервера.
     */
    fun startFailoverMonitor(service: Service) {
        val enabledByPreference = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, false)
        val requiredForServiceApps = ServiceAppsRoutingManager.requiresNonRussianExit()
        if (!enabledByPreference && !requiredForServiceApps) {
            Log.d(AppConfig.TAG, "Failover: disabled in preferences and not required by service-app routing")
            quickProbeOnNextStart.set(false)
            return
        }

        val quickInitialProbe = quickProbeOnNextStart.getAndSet(false)
        stopFailoverMonitor()
        isMonitoring = true
        consecutiveStallSeconds = 0
        isProbing.set(false)

        monitorJob = scope.launch {
            Log.i(AppConfig.TAG, "Failover monitor started. Waiting 10s warmup...")
            delay(10_000L) // Начальный прогрев туннеля

            if (quickInitialProbe && isActive && isMonitoring && V2RayServiceManager.isRunning()) {
                GeekModeLogger.log("Failover", "Running an early health check for the selected reserve profile")
                runHeartbeatCheck(service)
            }

            while (isActive && isMonitoring && V2RayServiceManager.isRunning()) {
                val intervalSec = MmkvManager.decodeSettingsString(AppConfig.PREF_AUTO_FAILOVER_INTERVAL_SEC, "30")?.toLongOrNull() ?: 30L
                val checkIntervalMs = (intervalSec.coerceAtLeast(5L)) * 1000L

                delay(checkIntervalMs)
                if (!isActive || !isMonitoring || !V2RayServiceManager.isRunning()) break

                // Периодический Heartbeat
                runHeartbeatCheck(service)
            }
        }
    }

    /**
     * Останавливает фоновый мониторинг.
     */
    fun stopFailoverMonitor() {
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        consecutiveStallSeconds = 0
        isProbing.set(false)
        Log.d(AppConfig.TAG, "Failover monitor stopped")
    }

    fun requestQuickProbeOnNextStart() {
        quickProbeOnNextStart.set(true)
    }

    fun cancelQuickProbeOnNextStart() {
        quickProbeOnNextStart.set(false)
    }

    /**
     * Принимает сэмпл скорости трафика от коллектора (вызывается раз в секунду).
     * Используется для мгновенной детекции затыков:
     * если есть исходящий трафик но входящий упал — туннель заглушен.
     */
    fun onTrafficSample(service: Service?, proxyUpRate: Double, proxyDownRate: Double) {
        if (!isMonitoring || service == null || !V2RayServiceManager.isRunning()) return
        val smartEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_SMART_FAILOVER_ENABLED, false)
        if (!smartEnabled) return
        if (isProbing.get()) return

        val thresholdKbps = MmkvManager.decodeSettingsString(AppConfig.PREF_SMART_FAILOVER_THRESHOLD_KBPS, "3")?.toDoubleOrNull() ?: 3.0
        val stallSec = MmkvManager.decodeSettingsString(AppConfig.PREF_SMART_FAILOVER_STALL_SEC, "3")?.toIntOrNull() ?: 3

        val thresholdBytes = thresholdKbps * 1024.0
        val minUplinkBytes = 1500.0 // 1.5 KB/s порог исходящей активности

        if (proxyUpRate >= minUplinkBytes && proxyDownRate < thresholdBytes) {
            consecutiveStallSeconds++
            GeekModeLogger.log(
                "Failover",
                "Smart Failover: Potential stall #$consecutiveStallSeconds (Up: ${"%.1f".format(proxyUpRate / 1024)} KB/s, Down: ${"%.1f".format(proxyDownRate / 1024)} KB/s)"
            )

            if (consecutiveStallSeconds >= stallSec) {
                consecutiveStallSeconds = 0
                triggerExpressProbe(
                    service,
                    "Traffic stall: Up ${"%.1f".format(proxyUpRate / 1024)} KB/s >= 1.5 KB/s but Down ${"%.1f".format(proxyDownRate / 1024)} KB/s < ${thresholdKbps} KB/s for ${stallSec}s"
                )
            }
        } else {
            consecutiveStallSeconds = 0
        }
    }

    /**
     * Выполняет экспресс-проверку текущего туннеля при подозрении на затык.
     */
    private fun triggerExpressProbe(service: Service, reason: String) {
        scope.launch {
            if (!isProbing.compareAndSet(false, true)) return@launch
            try {
                GeekModeLogger.log("Failover", "Triggering express probe. Reason: $reason")
                val health = testTunnelHealth(2000L)
                if (!health.isHealthy) {
                    GeekModeLogger.log("Failover", "Express tunnel probes all failed (${health.summary()}). Initiating failover...")
                    handleServerFailure(service, reason)
                } else {
                    val delay = health.delayMillis.takeIf { it > 0L } ?: Long.MAX_VALUE
                    AutoBackupManager.recordActiveHeartbeatSuccess(MmkvManager.getSelectServer(), delay)
                    GeekModeLogger.log("Failover", "Express tunnel probes passed (${health.summary()}); keeping the current profile.")
                }
            } finally {
                isProbing.set(false)
            }
        }
    }

    /**
     * Выполняет плановую проверку туннеля (Heartbeat).
     */
    private suspend fun runHeartbeatCheck(service: Service) {
        if (!isProbing.compareAndSet(false, true)) return
        try {
            val health = testTunnelHealth(3000L)
            if (!health.isHealthy) {
                GeekModeLogger.log("Failover", "Heartbeat tunnel probes failed (${health.summary()}). Retrying in 1.5s...")
                delay(1500L)
                val retryHealth = testTunnelHealth(3000L)
                if (!retryHealth.isHealthy) {
                    GeekModeLogger.log("Failover", "Heartbeat retry failed (${retryHealth.summary()}). Initiating failover...")
                    handleServerFailure(service, "Heartbeat health check failed twice: ${retryHealth.summary()}")
                } else {
                    val retryDelay = retryHealth.delayMillis.takeIf { it > 0L } ?: Long.MAX_VALUE
                    AutoBackupManager.recordActiveHeartbeatSuccess(MmkvManager.getSelectServer(), retryDelay)
                    GeekModeLogger.log("Failover", "Heartbeat retry recovered (${retryHealth.summary()}); keeping the current profile.")
                }
            } else {
                val delay = health.delayMillis.takeIf { it > 0L } ?: Long.MAX_VALUE
                AutoBackupManager.recordActiveHeartbeatSuccess(MmkvManager.getSelectServer(), delay)
                GeekModeLogger.log("Failover", "Heartbeat tunnel probes passed (${health.summary()}); keeping the current profile.")
            }
        } finally {
            isProbing.set(false)
        }
    }

    /**
     * Тестирует задержку через активный туннель xray с жестким таймаутом.
     */
    private suspend fun testTunnelDelay(timeoutMs: Long): Long = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs) {
                val delayUrl = SettingsManager.getDelayTestUrl()
                val res = V2RayServiceManager.measureDelay(delayUrl)
                if (res > 0) res else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /** The active profile is healthy only when ping and exit-IP checks both pass. */
    private suspend fun testTunnelHealth(timeoutMs: Long): TunnelHealth = kotlinx.coroutines.coroutineScope {
        val maxAcceptedDelay = SettingsManager.getAutoFailoverMaxPingMillis()
        val timeout = maxOf(timeoutMs, maxAcceptedDelay + 500L).coerceIn(1000L, 6000L).toInt()
        val delayProbe = async(Dispatchers.IO) { testTunnelDelay(timeout.toLong()) }
        val exitIpProbe = async(Dispatchers.IO) {
            try {
                SpeedtestManager.getRemoteIPDetails(timeout)
            } catch (_: Exception) {
                null
            }
        }

        val delayMillis = delayProbe.await()
        val exitIp = exitIpProbe.await()
        val exitIpAvailable = exitIp != null
        val requireNonRussianExit = ServiceAppsRoutingManager.requiresNonRussianExit()
        val delayProbePassed = delayMillis in 1L..maxAcceptedDelay
        val exitCountryAccepted = exitIpAvailable &&
            (!requireNonRussianExit || ServiceAppsRoutingManager.isKnownNonRussianCountry(exitIp?.country))
        if (delayProbePassed && exitCountryAccepted) {
            TunnelHealth(delayMillis, maxAcceptedDelay, exitIpAvailable, exitIp?.country, requireNonRussianExit)
        } else {
            val fallbackAvailable = withContext(Dispatchers.IO) {
                SpeedtestManager.isUrlReachableViaActiveProxy(FALLBACK_TUNNEL_PROBE_URL, timeout)
            }
            TunnelHealth(delayMillis, maxAcceptedDelay, exitIpAvailable, exitIp?.country, requireNonRussianExit, fallbackAvailable)
        }
    }

    /**
     * Обрабатывает подтверждённый сбой сервера.
     * Проводит арбитраж физической сети и переключает сервер если интернет есть.
     */
    private suspend fun handleServerFailure(service: Service, reason: String) {
        val physicalState = arbitratePhysicalNetwork(service)
        GeekModeLogger.log("Failover", "Physical network arbitration result: $physicalState (Trigger: $reason)")

        when (physicalState) {
            PhysicalNetworkState.NO_INTERNET -> {
                GeekModeLogger.log("Failover", "Physical network is down. Aborting failover, staying on current node.")
            }
            PhysicalNetworkState.WHITELIST_ONLY,
            PhysicalNetworkState.FULL_INTERNET -> {
                val currentGuid = MmkvManager.getSelectServer()
                if (currentGuid != null && MmkvManager.quarantineProfileForAutoFailover(currentGuid)) {
                    GeekModeLogger.log(
                        "Failover",
                        "Quarantined failed profile $currentGuid for ${MmkvManager.getAutoFailoverQuarantineMinutes()} minutes"
                    )
                }
                if (currentGuid != null) {
                    AutoBackupManager.recordBackupCheckFailure(currentGuid)
                }

                val backupGuids = AutoBackupManager.getBackupGuids(currentGuid, requireFresh = true)
                var nextGuid: String? = null
                for (candidateGuid in backupGuids) {
                    if (NodeTesterManager.verifyProfile(
                            service,
                            candidateGuid,
                            showStatus = false,
                            customTimeoutMs = 5_500,
                            skipSpeedCheck = true
                        )
                    ) {
                        nextGuid = candidateGuid
                        break
                    }
                    AutoBackupManager.recordBackupCheckFailure(candidateGuid)
                    MmkvManager.quarantineProfileForAutoFailover(candidateGuid)
                    GeekModeLogger.log("Failover", "Reserve profile $candidateGuid failed the exit-IP/ping eligibility check")
                }
                if (nextGuid == null) {
                    GeekModeLogger.log("Failover", "No verified reserve profile is ready; starting a full profile scan")
                    NotificationManager.showFailoverNotification()
                    requestQuickProbeOnNextStart()
                    if (!SmartConnectManager.switchServer(service)) {
                        cancelQuickProbeOnNextStart()
                        GeekModeLogger.log("Failover", "Full profile scan did not find a working alternative")
                    }
                    return
                }

                val profile = MmkvManager.decodeServerConfig(nextGuid)
                val remarks = profile?.remarks ?: nextGuid
                GeekModeLogger.log("Failover", "Hot-swapping from $currentGuid → $nextGuid ($remarks)")
                NotificationManager.showFailoverNotification()
                MmkvManager.setSelectServer(nextGuid)
                MmkvManager.saveLastConnectedServer(nextGuid)
                requestQuickProbeOnNextStart()
                MessageUtil.sendMsg2Service(service, AppConfig.MSG_STATE_SWITCH_SERVER, "")
                stopFailoverMonitor()
            }
        }
    }

    /** Выбирает следующий доступный профиль, пропуская карантинные. */
    fun selectNextServer(currentGuid: String?): String? {
        AutoBackupManager.getBackupGuids(currentGuid, requireFresh = true).firstOrNull()?.let { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            GeekModeLogger.log("Failover", "Selected verified reserve profile: ${profile?.remarks ?: guid} ($guid)")
            return guid
        }

        val fallback = SmartConnectManager.filterServers(
            MmkvManager.decodeServerList(),
            excludeGuid = currentGuid
        ).firstOrNull()
        if (fallback != null) {
            GeekModeLogger.log("Failover", "No fresh reserve; selected first eligible profile for fallback scan: ${fallback.second.remarks} (${fallback.first})")
        }
        return fallback?.first
    }

    /**
     * Физический арбитраж сети в обход VPN.
     * Проверяет ya.ru и google.com через реальные сетевые адаптеры устройства.
     */
    suspend fun arbitratePhysicalNetwork(context: Context): PhysicalNetworkState {
        val checkEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_NETWORK_CHECK_ENABLED, true)
        if (!checkEnabled) return PhysicalNetworkState.FULL_INTERNET // assume full internet if check is disabled

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return PhysicalNetworkState.NO_INTERNET

        val networks = cm.allNetworks
        var physicalNet: android.net.Network? = null
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                physicalNet = net
                break
            }
        }

        if (physicalNet == null) return PhysicalNetworkState.NO_INTERNET

        var yaOk = false
        var googleOk = false
        val net = physicalNet

        var url1 = MmkvManager.decodeSettingsString(AppConfig.PREF_NETWORK_CHECK_URL_1, "google.com") ?: "google.com"
        var url2 = MmkvManager.decodeSettingsString(AppConfig.PREF_NETWORK_CHECK_URL_2, "ya.ru") ?: "ya.ru"
        if (url1.isBlank()) url1 = "google.com"
        if (url2.isBlank()) url2 = "ya.ru"
        
        // Add scheme if missing
        if (!url1.startsWith("http")) url1 = "https://$url1"
        if (!url2.startsWith("http")) url2 = "https://$url2"

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val yaDef = async { checkDirectUrl(net, url2, "HEAD", 1500) }
            val googleDef = async { checkDirectUrl(net, url1, "GET", 1500) }
            yaOk = yaDef.await()
            googleOk = googleDef.await()
        }

        return when {
            yaOk && googleOk -> PhysicalNetworkState.FULL_INTERNET
            !yaOk && googleOk -> PhysicalNetworkState.FULL_INTERNET  // зарубежный Wi-Fi
            yaOk && !googleOk -> PhysicalNetworkState.WHITELIST_ONLY // оператор блокирует внешний интернет
            else -> PhysicalNetworkState.NO_INTERNET
        }
    }

    private fun checkDirectUrl(network: android.net.Network, urlStr: String, method: String, timeoutMs: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = network.openConnection(url) as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}

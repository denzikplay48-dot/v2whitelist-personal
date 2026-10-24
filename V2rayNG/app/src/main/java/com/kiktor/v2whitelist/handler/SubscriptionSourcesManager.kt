package com.kiktor.v2whitelist.handler

import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.util.HttpUtil
import com.kiktor.v2whitelist.util.JsonUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.MessageDigest

/** Manages catalogs that list independent subscription URLs. */
object SubscriptionSourcesManager {
    const val BUILTIN_SOURCE_ID = "builtin_original_subscriptions"
    const val DEFAULT_CATALOG_URL =
        "https://raw.githubusercontent.com/denzikplay48-dot/Sub-ExclaveD/main/Sub"

    data class Source(
        var id: String = "",
        var name: String = "",
        var url: String = "",
        var enabled: Boolean = true,
        var lastSyncedAt: Long = 0L,
        var subscriptionCount: Int = 0,
        var lastError: String = ""
    )

    private data class CatalogEntry(val url: String, val name: String)
    private val syncMutex = Mutex()

    fun loadSources(): MutableList<Source> {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_SUBSCRIPTION_SOURCES)
        if (raw.isNullOrBlank()) {
            val initial = mutableListOf(sourceFor(DEFAULT_CATALOG_URL).apply { name = "Sub-ExclaveD" })
            saveSources(initial)
            return initial
        }

        val sources = runCatching {
            JsonUtil.fromJson(raw, Array<Source>::class.java)?.toMutableList()
        }.getOrNull() ?: mutableListOf()

        val unique = sources
            .filter { normalizeCatalogUrl(it.url) != null }
            .distinctBy { normalizeCatalogUrl(it.url) }
            .onEach { source ->
                source.url = normalizeCatalogUrl(source.url).orEmpty()
                if (source.id.isBlank()) source.id = sourceId(source.url)
                if (source.name.isBlank()) source.name = displayName(source.url)
            }
            .toMutableList()
        if (unique.size != sources.size) saveSources(unique)
        return unique
    }

    fun addSource(input: String): Source? {
        val url = normalizeCatalogUrl(input) ?: return null
        val sources = loadSources()
        if (sources.any { normalizeCatalogUrl(it.url) == url }) return null
        val source = sourceFor(url)
        sources.add(source)
        saveSources(sources)
        return source
    }

    fun normalizeSourceUrl(input: String): String? = normalizeCatalogUrl(input)

    fun isSourceUrlAdded(input: String): Boolean {
        val url = normalizeCatalogUrl(input) ?: return false
        return loadSources().any { normalizeCatalogUrl(it.url) == url }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean): Boolean {
        val sources = loadSources()
        val source = sources.firstOrNull { it.id == sourceId } ?: return false
        source.enabled = enabled
        if (enabled) source.lastSyncedAt = 0L
        saveSources(sources)
        return true
    }

    fun setBuiltinSourceEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_BUILTIN_SUBSCRIPTION_SOURCE_ENABLED, enabled)
    }

    fun isBuiltinSourceEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BUILTIN_SUBSCRIPTION_SOURCE_ENABLED, true)

    fun enabledCatalogSourceIds(): Set<String> =
        loadSources().filter { it.enabled }.map { it.id }.toSet()

    fun isSubscriptionSourceEnabled(
        sub: SubscriptionHelper.CustomSubData,
        enabledCatalogIds: Set<String> = enabledCatalogSourceIds(),
        builtinEnabled: Boolean = isBuiltinSourceEnabled()
    ): Boolean = isSubscriptionSourceEnabled(sub.id, sub.catalogSourceIds, enabledCatalogIds, builtinEnabled)

    fun isSubscriptionSourceEnabled(
        subId: String,
        catalogSourceIds: List<String>,
        enabledCatalogIds: Set<String> = enabledCatalogSourceIds(),
        builtinEnabled: Boolean = isBuiltinSourceEnabled()
    ): Boolean {
        if (DefaultSubscriptions.PREPOPULATED_SUBS.any { it.id == subId }) return builtinEnabled
        if (catalogSourceIds.isEmpty()) return true
        return catalogSourceIds.any { enabledCatalogIds.contains(it) }
    }

    fun disabledSubscriptionGuids(): Set<String> {
        val enabledCatalogIds = enabledCatalogSourceIds()
        val builtinEnabled = isBuiltinSourceEnabled()
        return SubscriptionHelper.loadCustomSubs().asSequence()
            .filter { sub ->
                !sub.enabled || !isSubscriptionSourceEnabled(
                    sub.id, sub.catalogSourceIds, enabledCatalogIds, builtinEnabled
                )
            }
            .map { "custom_sub_${it.id}" }
            .toSet()
    }

    fun removeSource(sourceId: String) {
        val sources = loadSources()
        if (sources.removeAll { it.id == sourceId }) saveSources(sources)

        val customSubs = SubscriptionHelper.loadCustomSubs().toMutableList()
        val removedSubIds = mutableListOf<String>()
        val iterator = customSubs.iterator()
        while (iterator.hasNext()) {
            val sub = iterator.next()
            if (!sub.catalogSourceIds.contains(sourceId)) continue
            sub.catalogSourceIds = sub.catalogSourceIds.filterNot { it == sourceId }
            if (sub.catalogSourceIds.isEmpty()) {
                removedSubIds.add(sub.id)
                iterator.remove()
            }
        }
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, JsonUtil.toJson(customSubs))
        removedSubIds.forEach { id ->
            MmkvManager.removeSubscription("custom_sub_$id")
            MmkvManager.removeServerViaSubid("custom_sub_$id")
        }
    }

    /** Fetches enabled catalogs one at a time and reconciles their subscription URLs. */
    suspend fun syncCatalogs(force: Boolean = false): Int = syncMutex.withLock {
        syncCatalogsLocked(force)
    }

    private suspend fun syncCatalogsLocked(force: Boolean): Int = withContext(Dispatchers.IO) {
        val sources = loadSources()
        if (sources.none { it.enabled }) return@withContext 0

        val customSubs = SubscriptionHelper.loadCustomSubs().toMutableList()
        val defaultUrls = DefaultSubscriptions.PREPOPULATED_SUBS
            .flatMap { it.url.split('|', '\n') }
            .mapNotNull(::normalizeFeedUrl)
            .toSet()
        var changedCount = 0
        var customSubsChanged = false

        for (source in sources.filter { it.enabled }) {
            val now = System.currentTimeMillis()
            if (!force && source.lastSyncedAt > 0L && now - source.lastSyncedAt < 5 * 60 * 1000L) {
                continue
            }
            val content = fetchCatalog(source.url)
            if (content.isNullOrBlank()) {
                source.lastError = "Unable to fetch catalog"
                continue
            }

            val entries = parseCatalog(content)
            if (entries.isEmpty()) {
                source.lastError = "No valid subscription URLs found"
                continue
            }

            val currentSourceIds = mutableSetOf<String>()
            for (entry in entries) {
                val normalizedUrl = normalizeFeedUrl(entry.url) ?: continue
                if (defaultUrls.contains(normalizedUrl)) continue

                val manualDuplicate = customSubs.firstOrNull { sub ->
                    sub.catalogSourceIds.isEmpty() && sub.id != AppConfig.LOCAL_PROFILES_SUBSCRIPTION_ID &&
                        sub.url.split('|', '\n').mapNotNull(::normalizeFeedUrl).contains(normalizedUrl)
                }
                if (manualDuplicate != null) continue

                val id = "catalog_${sha256(normalizedUrl).take(20)}"
                currentSourceIds.add(id)
                val existing = customSubs.firstOrNull { it.id == id }
                if (existing == null) {
                    customSubs.add(
                        SubscriptionHelper.CustomSubData(
                            id = id,
                            name = entry.name.ifBlank { displayName(normalizedUrl) },
                            url = normalizedUrl,
                            enabled = true,
                            targetScenarios = listOf(
                                SubscriptionHelper.SCENARIO_WHITELIST,
                                SubscriptionHelper.SCENARIO_BLACKLIST
                            ),
                            catalogSourceIds = listOf(source.id)
                        )
                    )
                    customSubsChanged = true
                    changedCount++
                } else {
                    val sourceIds = (existing.catalogSourceIds + source.id).distinct()
                    if (sourceIds != existing.catalogSourceIds) {
                        existing.catalogSourceIds = sourceIds
                        customSubsChanged = true
                    }
                    if (existing.url != normalizedUrl) {
                        existing.url = normalizedUrl
                        customSubsChanged = true
                    }
                }
            }

            // Remove stale links only after a non-empty, successfully parsed catalog response.
            for (sub in customSubs.toList()) {
                if (sub.catalogSourceIds.contains(source.id) && !currentSourceIds.contains(sub.id)) {
                    sub.catalogSourceIds = sub.catalogSourceIds.filterNot { it == source.id }
                    customSubsChanged = true
                    if (sub.catalogSourceIds.isEmpty()) {
                        customSubs.removeAll { it.id == sub.id }
                        MmkvManager.removeSubscription("custom_sub_${sub.id}")
                        MmkvManager.removeServerViaSubid("custom_sub_${sub.id}")
                    }
                }
            }

            source.lastSyncedAt = System.currentTimeMillis()
            source.subscriptionCount = currentSourceIds.size
            source.lastError = ""
        }

        if (customSubsChanged) {
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_SUB_URLS, JsonUtil.toJson(customSubs))
        }
        saveSources(sources)
        changedCount
    }

    private suspend fun fetchCatalog(url: String): String? {
        var result = HttpUtil.getUrlContent(url, 10000)
        if (!result.isNullOrBlank()) return result
        val httpPort = if (V2RayServiceManager.isRunning()) SettingsManager.getHttpPort() else 0
        if (httpPort > 0) result = HttpUtil.getUrlContent(url, 10000, httpPort)
        return result
    }

    private fun parseCatalog(content: String): List<CatalogEntry> {
        val byUrl = linkedMapOf<String, CatalogEntry>()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.lastIndexOf('#')
            val rawUrl = if (separator > 0) line.substring(0, separator).trim() else line
            val url = normalizeFeedUrl(rawUrl) ?: continue
            val name = if (separator > 0) line.substring(separator + 1).trim() else ""
            byUrl.putIfAbsent(url, CatalogEntry(url, name))
        }
        return byUrl.values.toList()
    }

    private fun sourceFor(url: String) = Source(
        id = sourceId(url),
        name = displayName(url),
        url = url,
        enabled = true
    )

    private fun saveSources(sources: List<Source>) {
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_SOURCES, JsonUtil.toJson(sources))
    }

    private fun sourceId(url: String) = "catalog_source_${sha256(url).take(16)}"

    private fun displayName(url: String): String = runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').ifBlank { "catalog" }
        "${uri.host ?: "source"}/$path"
    }.getOrDefault("Subscription catalog")

    private fun normalizeCatalogUrl(input: String): String? {
        var url = input.trim()
        val githubBlob = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$", RegexOption.IGNORE_CASE)
        githubBlob.matchEntire(url)?.let { match ->
            url = "https://raw.githubusercontent.com/${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}/${match.groupValues[4]}"
        }
        if (!Utils.isValidUrl(url)) return null
        return url
    }

    private fun normalizeFeedUrl(input: String): String? {
        var url = input.trim()
        val githubBlob = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$", RegexOption.IGNORE_CASE)
        githubBlob.matchEntire(url)?.let { match ->
            url = "https://raw.githubusercontent.com/${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}/${match.groupValues[4]}"
        }
        if (!Utils.isValidUrl(url) && !Utils.isValidSubUrl(url)) return null
        return url
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

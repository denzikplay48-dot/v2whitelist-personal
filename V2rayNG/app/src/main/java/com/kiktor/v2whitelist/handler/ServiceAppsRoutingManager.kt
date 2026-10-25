package com.kiktor.v2whitelist.handler

import android.content.Context
import com.kiktor.v2whitelist.AppConfig
import java.util.Locale

/** Routing policy for the user-selected apps that can switch between the phone network and VPN. */
object ServiceAppsRoutingManager {

    fun selectedPackages(): Set<String> =
        MmkvManager.decodeSettingsStringSet(AppConfig.PREF_SERVICE_APPS_SET)?.toSet() ?: emptySet()

    /** Apps already controlled by the permanent split-tunneling list cannot be overridden here. */
    fun switchablePackages(): Set<String> {
        val selected = selectedPackages()
        val permanentRulesEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == true
        val permanentPackages = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet().orEmpty()
        return if (permanentRulesEnabled && permanentPackages.isNotEmpty()) selected - permanentPackages else selected
    }

    fun isBypassEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_SERVICE_APPS_BYPASS, true)

    fun requiresNonRussianExit(): Boolean = switchablePackages().isNotEmpty() && !isBypassEnabled()

    fun excludeRussianExitEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_EXCLUDE_RUSSIAN_EXIT_IP, false)

    fun requiresNonRussianExitForSelection(): Boolean =
        requiresNonRussianExit() || excludeRussianExitEnabled()

    fun isKnownNonRussianCountry(country: String?): Boolean {
        val value = country?.trim()?.replace('_', ' ')?.takeIf { it.isNotBlank() } ?: return false
        val normalized = value.uppercase(Locale.ROOT)
        if (normalized in setOf("UNKNOWN", "UNAVAILABLE", "N/A", "XX", "ZZ", "--")) return false
        if (normalized in setOf("RU", "RUS", "RUSSIA", "RUSSIAN FEDERATION", "РОССИЯ", "РОССИЙСКАЯ ФЕДЕРАЦИЯ")) {
            return false
        }

        val normalizedName = normalized.filter { it.isLetterOrDigit() }
        for (countryCode in Locale.getISOCountries()) {
            val locale = Locale("", countryCode)
            val iso3 = try {
                locale.getISO3Country().uppercase(Locale.ROOT)
            } catch (_: Exception) {
                ""
            }
            if (normalized == countryCode || normalized == iso3) {
                return countryCode != "RU"
            }

            val names = listOf(
                locale.getDisplayCountry(Locale.ENGLISH),
                locale.getDisplayCountry(Locale("ru")),
                locale.displayCountry
            )
            if (names.any {
                    it.isNotBlank() && it.uppercase(Locale.ROOT).filter { character -> character.isLetterOrDigit() } == normalizedName
                }
            ) {
                return countryCode != "RU"
            }
        }
        return false
    }

    /**
     * Changes the selected apps to VPN mode only after a known non-Russian exit is available.
     * While a replacement is being scanned, the established Android VPN interface still keeps
     * the apps excluded, so they do not briefly leak through an ineligible profile.
     */
    suspend fun prepareVpnMode(context: Context): Boolean {
        if (switchablePackages().isEmpty()) return false
        if (!isBypassEnabled()) return true

        MmkvManager.encodeSettings(AppConfig.PREF_SERVICE_APPS_BYPASS, false)
        if (!V2RayServiceManager.isRunning()) return true

        val currentGuid = MmkvManager.getSelectServer()
        if (!currentGuid.isNullOrBlank() && NodeTesterManager.verifyProfile(
                context,
                currentGuid,
                showStatus = false,
                customTimeoutMs = 5_500,
                ignoreCustomEndpoint = false,
                skipSpeedCheck = true
            )
        ) {
            return true
        }

        GeekModeLogger.log("ServiceApps", "Active VPN exit is not eligible; searching for a verified non-Russian profile")
        val switched = SmartConnectManager.switchServer(context)
        if (!switched) {
            MmkvManager.encodeSettings(AppConfig.PREF_SERVICE_APPS_BYPASS, true)
            return false
        }
        return true
    }
}

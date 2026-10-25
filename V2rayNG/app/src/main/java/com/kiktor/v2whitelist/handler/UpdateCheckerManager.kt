package com.kiktor.v2whitelist.handler

import android.content.Context
import android.os.Build
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.BuildConfig
import com.kiktor.v2whitelist.dto.CheckUpdateResult
import com.kiktor.v2whitelist.dto.GitHubRelease
import com.kiktor.v2whitelist.util.HttpUtil
import com.kiktor.v2whitelist.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val response = getGithubContent(
            AppConfig.APP_API_URL,
            getGithubConnectionPorts(),
            timeoutMs = 12_000
        )
        val releases = JsonUtil.fromJson(response, Array<GitHubRelease>::class.java)
            ?.filter { it.tagName.isNotBlank() }
            .orEmpty()
        val stableReleases = releases.filterNot { it.prerelease }
        // This fork publishes its numbered releases as GitHub pre-releases. If there are no
        // stable releases, still offer the newest fork build even when the beta option is off.
        val eligibleReleases = if (includePreRelease || stableReleases.isEmpty()) releases else stableReleases
        val latestRelease = eligibleReleases.maxWithOrNull { first, second ->
            compareVersions(first.tagName.removePrefix("v"), second.tagName.removePrefix("v"))
        } ?: throw IllegalStateException("No releases found in the GitHub repository")

        val latestVersion = latestRelease.tagName.removePrefix("v")
        Log.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            val apkFileName = downloadUrl.substringAfterLast("/")
            val checksumUrl = latestRelease.assets.firstOrNull { it.name == "sha256sum.txt" }?.browserDownloadUrl
            
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                checksumUrl = checksumUrl,
                apkFileName = apkFileName,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    suspend fun downloadApk(context: Context, downloadUrl: String, onProgress: ((Int) -> Unit)? = null): File? = withContext(Dispatchers.IO) {
        for (port in getGithubConnectionPorts(preferProxy = true)) {
            val apkFile = downloadApkViaPort(context, downloadUrl, port, onProgress)
            if (apkFile != null) return@withContext apkFile
        }
        null
    }

    private fun getGithubConnectionPorts(preferProxy: Boolean = false): List<Int> {
        if (!com.kiktor.v2whitelist.handler.V2RayServiceManager.isRunning()) return listOf(0)
        val httpPort = SettingsManager.getHttpPort()
        return if (preferProxy) listOf(httpPort, 0).distinct() else listOf(0, httpPort).distinct()
    }

    private fun getGithubContent(url: String, ports: List<Int>, timeoutMs: Int): String {
        var lastError: Exception? = null
        for (port in ports) {
            try {
                return HttpUtil.getUrlContentWithUserAgent(
                    url,
                    "ProBel-D/${BuildConfig.VERSION_NAME}",
                    timeoutMs,
                    port
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(AppConfig.TAG, "GitHub request failed using ${if (port == 0) "system network" else "VPN proxy"}: ${e.message}")
            }
        }
        throw IllegalStateException("Failed to contact GitHub. Check the VPN connection and try again.", lastError)
    }

    private fun downloadApkViaPort(
        context: Context,
        downloadUrl: String,
        port: Int,
        onProgress: ((Int) -> Unit)?
    ): File? {
        val connection = HttpUtil.createProxyConnection(downloadUrl, port, 30_000, 30_000, true)
        if (connection == null) {
            Log.w(AppConfig.TAG, "Unable to create GitHub APK connection (port=$port)")
            return null
        }

        try {
            val cacheFolder = context.externalCacheDir ?: context.cacheDir
            val fileName = downloadUrl.substringAfterLast("/", "update.apk")
            cacheFolder.listFiles { file -> file.name.endsWith(".apk") && file.name != fileName }
                ?.forEach { it.delete() }

            val apkFile = File(cacheFolder, fileName)
            Log.i(AppConfig.TAG, "Downloading APK to: ${apkFile.absolutePath} (port=$port)")

            var downloadedBytes = apkFile.takeIf { it.exists() }?.length() ?: 0L
            if (downloadedBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                Log.i(AppConfig.TAG, "Requesting range: bytes=$downloadedBytes-")
            }

            connection.connect()
            val responseCode = connection.responseCode
            Log.i(AppConfig.TAG, "APK response code: $responseCode (port=$port)")

            if (responseCode == 416) {
                Log.i(AppConfig.TAG, "Range not satisfiable; checksum will verify the existing APK.")
                return apkFile
            }

            val append = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                downloadedBytes = 0L
                if (apkFile.exists()) apkFile.delete()
            }

            val contentLength = connection.contentLength
            val fileLength = if (append) downloadedBytes + contentLength else contentLength.toLong()
            var lastProgress = 0

            FileOutputStream(apkFile, append).use { outputStream ->
                connection.inputStream.use { inputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = downloadedBytes
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (fileLength > 0) {
                            val progress = (totalRead * 100 / fileLength).toInt()
                            if (progress > lastProgress) {
                                lastProgress = progress
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                }
            }
            Log.i(AppConfig.TAG, "APK download completed")
            return apkFile
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "APK download attempt failed (port=$port): ${e.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            val num2 = if (i < v2.size) v2[i].takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val isFdroid = BuildConfig.APPLICATION_ID.endsWith(".fdroid")
        
        // Filter assets by flavor first
        val flavorAssets = release.assets.filter { asset ->
            if (isFdroid) {
                asset.name.contains("fdroid", ignoreCase = true)
            } else {
                !asset.name.contains("fdroid", ignoreCase = true)
            }
        }
        
        // Search specific ABI first
        var asset = flavorAssets.firstOrNull { 
            it.name.contains(abi, ignoreCase = true) 
        }
        
        // Fallback to universal
        if (asset == null) {
            asset = flavorAssets.firstOrNull {
                it.name.contains("universal", ignoreCase = true)
            }
        }
        
        // Fallback for old universal without flavor prefix
        if (asset == null) {
            asset = release.assets.firstOrNull { 
                it.name.contains("universal", ignoreCase = true) && !it.name.contains("fdroid", ignoreCase = true) 
            }
        }
        
        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }

    suspend fun verifyChecksum(apkFile: File, checksumUrl: String, apkFileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = getGithubContent(
                checksumUrl,
                getGithubConnectionPorts(),
                timeoutMs = 12_000
            )
            
            var expectedHash: String? = null
            for (line in response.lines()) {
                if (line.contains(apkFileName)) {
                    expectedHash = line.substringBefore(" ").trim()
                    break
                }
            }
            
            if (expectedHash == null) {
                Log.e(AppConfig.TAG, "Checksum for $apkFileName not found in sha256sum.txt")
                return@withContext false
            }
            
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val fis = java.io.FileInputStream(apkFile)
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            fis.close()
            
            val hashBytes = digest.digest()
            val actualHash = hashBytes.joinToString("") { "%02x".format(it) }
            
            Log.i(AppConfig.TAG, "Expected SHA-256: $expectedHash, Actual: $actualHash")
            return@withContext expectedHash.equals(actualHash, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to verify checksum: ${e.message}", e)
            return@withContext false
        }
    }
}

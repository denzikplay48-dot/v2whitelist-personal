package com.kiktor.v2whitelist.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.AngConfigManager
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalProfilesActivity : HelperBaseActivity() {

    private lateinit var adapter: LocalProfileAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_local_profiles,
            showHomeAsUp = true,
            title = getString(R.string.local_profiles_title)
        )

        tvEmpty = findViewById(R.id.tv_local_profiles_empty)
        adapter = LocalProfileAdapter(
            onConnect = ::connectProfile,
            onShowQr = ::showProfileQr,
            onDelete = ::confirmDeleteProfile
        )
        findViewById<RecyclerView>(R.id.rv_local_profiles).apply {
            layoutManager = LinearLayoutManager(this@LocalProfilesActivity)
            adapter = this@LocalProfilesActivity.adapter
        }
        findViewById<View>(R.id.btn_add_local_profile).setOnClickListener { showImportOptions() }
        refreshProfiles()
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) refreshProfiles()
    }

    private fun refreshProfiles() {
        val customNames = SubscriptionHelper.loadCustomSubs().associate { it.id to it.name }
        val profiles = MmkvManager.decodeServerList().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val subscriptionId = profile.subscriptionId
            val isLocal = subscriptionId == AppConfig.LOCAL_PROFILES_SUBSCRIPTION_GUID ||
                subscriptionId == AppConfig.LOCAL_PROFILES_SUBSCRIPTION_ID
            val normalizedId = subscriptionId.removePrefix("custom_sub_")
            val sourceName = if (isLocal) {
                getString(R.string.local_profile_origin_local)
            } else {
                customNames[normalizedId]
                    ?: MmkvManager.decodeSubscription(subscriptionId)?.remarks
                    ?: MmkvManager.decodeSubscription("custom_sub_$normalizedId")?.remarks
                    ?: getString(R.string.local_profile_origin_subscription)
            }
            LocalProfileAdapter.ProfileRow(guid, profile, sourceName, isLocal)
        }
        adapter.submitProfiles(profiles)
        tvEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
    }

    private fun showImportOptions() {
        val options = arrayOf(
            getString(R.string.local_profile_import_clipboard),
            getString(R.string.local_profile_import_qr),
            getString(R.string.local_profile_import_file),
            getString(R.string.local_profile_import_text)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.local_profile_add)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFromClipboard()
                    1 -> launchQRCodeScanner { result -> result?.let(::importProfiles) }
                    2 -> launchFileChooser("*/*") { uri -> uri?.let(::importFile) }
                    3 -> showTextImportDialog()
                }
            }
            .show()
    }

    private fun importFromClipboard() {
        val content = Utils.getClipboard(this)
        if (content.isBlank()) {
            toast(R.string.local_profile_clipboard_empty)
        } else {
            importProfiles(content)
        }
    }

    private fun showTextImportDialog() {
        val input = EditText(this).apply {
            minLines = 5
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextIsSelectable(true)
            hint = getString(R.string.local_profile_import_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.local_profile_import_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> importProfiles(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFile(uri: Uri) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                toast(R.string.local_profile_file_empty)
            } else {
                importProfiles(content)
            }
        }
    }

    private fun importProfiles(content: String) {
        val imported = AngConfigManager.importProfilesOnly(
            content,
            AppConfig.LOCAL_PROFILES_SUBSCRIPTION_GUID
        )
        if (imported <= 0) {
            toast(R.string.toast_incorrect_protocol)
            return
        }
        toast(getString(R.string.title_import_config_count, imported))
        refreshProfiles()
    }

    private fun connectProfile(guid: String) {
        if (MmkvManager.decodeServerConfig(guid) == null) {
            toast(R.string.local_profile_missing)
            refreshProfiles()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONNECT_SERVER_GUID, guid)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun showProfileQr(profile: ProfileItem) {
        val bitmap = AngConfigManager.share2QRCode(profile)
        if (bitmap == null) {
            toast(R.string.local_profile_qr_unavailable)
            return
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(profile.remarks.ifBlank { getString(R.string.local_profiles_title) })
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDeleteProfile(guid: String) {
        if (guid == MmkvManager.getSelectServer() && V2RayServiceManager.isRunning()) {
            toast(R.string.local_profile_disconnect_before_delete)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_item_del_config)
            .setMessage(R.string.local_profile_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@setPositiveButton
                if (profile.subscriptionId != AppConfig.LOCAL_PROFILES_SUBSCRIPTION_GUID &&
                    profile.subscriptionId != AppConfig.LOCAL_PROFILES_SUBSCRIPTION_ID
                ) return@setPositiveButton
                MmkvManager.removeServer(guid)
                refreshProfiles()
            }
            .show()
    }
}

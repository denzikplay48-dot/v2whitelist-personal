package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.DefaultSubscriptions
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.handler.SubscriptionSourcesManager
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubscriptionSourcesActivity : BaseActivity() {
    private lateinit var adapter: SubscriptionSourceAdapter
    private lateinit var progress: ProgressBar
    private lateinit var addButton: View
    private var isUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_subscription_sources,
            showHomeAsUp = true,
            title = getString(R.string.title_subscription_sources_settings)
        )

        progress = findViewById(R.id.progress_subscription_sources)
        addButton = findViewById(R.id.btn_add_subscription_source)
        adapter = SubscriptionSourceAdapter(::onToggleSource, ::confirmRemoveSource)
        findViewById<RecyclerView>(R.id.rv_subscription_sources).apply {
            layoutManager = LinearLayoutManager(this@SubscriptionSourcesActivity)
            adapter = this@SubscriptionSourcesActivity.adapter
        }
        addButton.setOnClickListener { showAddSourceDialog() }
        refreshRows()

        lifecycleScope.launch {
            val needsInitialSync = SubscriptionSourcesManager.loadSources()
                .any { it.enabled && it.lastSyncedAt == 0L }
            if (needsInitialSync) {
                updateSubscriptions(showResult = false)
            } else {
                withContext(Dispatchers.IO) { SubscriptionSourcesManager.syncCatalogs() }
                refreshRows()
            }
        }
    }

    private fun refreshRows() {
        val builtin = SubscriptionSourcesManager.Source(
            id = SubscriptionSourcesManager.BUILTIN_SOURCE_ID,
            name = getString(R.string.subscription_sources_builtin),
            enabled = SubscriptionSourcesManager.isBuiltinSourceEnabled(),
            subscriptionCount = DefaultSubscriptions.PREPOPULATED_SUBS.size
        )
        adapter.submitItems(listOf(builtin) + SubscriptionSourcesManager.loadSources())
    }

    private fun showAddSourceDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.subscription_sources_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.subscription_sources_add_title)
            .setView(input)
            .setPositiveButton(R.string.subscription_sources_add) { _, _ ->
                val entered = input.text.toString().trim()
                if (SubscriptionSourcesManager.normalizeSourceUrl(entered) == null) {
                    toast(R.string.toast_invalid_url)
                    return@setPositiveButton
                }
                if (SubscriptionSourcesManager.isSourceUrlAdded(entered)) {
                    toast(R.string.subscription_sources_duplicate)
                    return@setPositiveButton
                }
                if (SubscriptionSourcesManager.addSource(entered) == null) {
                    toast(R.string.toast_invalid_url)
                    return@setPositiveButton
                }
                refreshRows()
                updateSubscriptions(showResult = true, forceSourceSync = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onToggleSource(source: SubscriptionSourcesManager.Source, enabled: Boolean) {
        if (source.id == SubscriptionSourcesManager.BUILTIN_SOURCE_ID) {
            SubscriptionSourcesManager.setBuiltinSourceEnabled(enabled)
        } else {
            SubscriptionSourcesManager.setSourceEnabled(source.id, enabled)
        }
        refreshRows()
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
        updateSubscriptions(showResult = false, forceSourceSync = true)
    }

    private fun confirmRemoveSource(source: SubscriptionSourcesManager.Source) {
        AlertDialog.Builder(this)
            .setTitle(R.string.subscription_sources_remove_title)
            .setMessage(R.string.subscription_sources_remove_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.subscription_sources_remove) { _, _ ->
                SubscriptionSourcesManager.removeSource(source.id)
                refreshRows()
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
                updateSubscriptions(showResult = false, forceSourceSync = true)
            }
            .show()
    }

    private fun updateSubscriptions(showResult: Boolean, forceSourceSync: Boolean = false) {
        if (isUpdating) return
        isUpdating = true
        progress.visibility = View.VISIBLE
        addButton.isEnabled = false
        adapter.setInteractionsEnabled(false)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SubscriptionHelper.updateSubscription(
                        this@SubscriptionSourcesActivity,
                        sequential = true,
                        forceSourceSync = forceSourceSync
                    )
                }
                if (showResult) toast(R.string.subscription_sources_added)
            } catch (e: Exception) {
                if (showResult) toast(R.string.subscription_sources_invalid)
            } finally {
                progress.visibility = View.GONE
                addButton.isEnabled = true
                isUpdating = false
                adapter.setInteractionsEnabled(true)
                refreshRows()
                MessageUtil.sendMsg2UI(this@SubscriptionSourcesActivity, AppConfig.MSG_STATE_RELOAD_SERVER_LIST, "")
            }
        }
    }
}

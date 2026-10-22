package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.view.Menu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.databinding.ActivityBypassListBinding
import com.kiktor.v2whitelist.dto.AppInfo
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsChangeManager
import com.kiktor.v2whitelist.util.AppManagerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/** Lets the user choose the independent app set controlled by the home-screen DNS/VPN switch. */
class ServiceAppsActivity : BaseActivity() {
    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }
    private var appsAll: List<AppInfo> = emptyList()
    private var adapter: PerAppProxyAdapter? = null
    private val selectedApps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_SERVICE_APPS_SET)
        ?.toMutableSet() ?: mutableSetOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.title_service_apps_selection)
        )

        binding.containerPerAppProxy.visibility = android.view.View.GONE
        binding.containerBypassApps.visibility = android.view.View.GONE
        binding.tvServiceAppsDescription.visibility = android.view.View.VISIBLE
        binding.tvServiceAppsDescription.text = getString(R.string.summary_service_apps_selection)
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val loaded = AppManagerUtil.loadNetworkAppList(this@ServiceAppsActivity)
                val collator = Collator.getInstance()
                loaded.filterNot { it.packageName == packageName }
                    .sortedWith(compareBy(collator) { it.appName })
            }
            appsAll = apps
            showApps(apps)
        }
    }

    private fun showApps(apps: List<AppInfo>) {
        adapter = PerAppProxyAdapter(
            apps,
            isSelected = selectedApps::contains,
            onToggle = { packageName ->
                if (!selectedApps.add(packageName)) selectedApps.remove(packageName)
                MmkvManager.encodeSettings(AppConfig.PREF_SERVICE_APPS_SET, selectedApps)
                SettingsChangeManager.makeRestartService()
            }
        )
        binding.recyclerView.adapter = adapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bypass_list, menu)
        val searchItem = menu.findItem(R.id.search_view)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val key = newText.orEmpty().trim()
                showApps(if (key.isEmpty()) appsAll else appsAll.filter {
                    it.appName.contains(key, ignoreCase = true) || it.packageName.contains(key, ignoreCase = true)
                })
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }
}

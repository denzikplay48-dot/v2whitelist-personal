package com.kiktor.v2whitelist.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.helper.ItemTouchHelperAdapter
import com.kiktor.v2whitelist.helper.ItemTouchHelperViewHolder
import com.kiktor.v2whitelist.util.Utils
import java.util.Collections

class CustomSubscriptionAdapter(
    private val items: MutableList<CustomSubscriptionsActivity.CustomSubItem>,
    private val onToggle: (position: Int, isEnabled: Boolean) -> Unit,
    private val onDelete: (position: Int) -> Unit,
    private val onEdit: (position: Int) -> Unit,
    private val onMoveCompleted: () -> Unit
) : RecyclerView.Adapter<CustomSubscriptionAdapter.ViewHolder>(), ItemTouchHelperAdapter {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view), ItemTouchHelperViewHolder {
        val cardView: MaterialCardView = view as MaterialCardView
        val tvName: TextView = view.findViewById(R.id.tv_sub_name)
        val tvUrl: TextView = view.findViewById(R.id.tv_sub_url)
        val tvProfileCount: TextView = view.findViewById(R.id.tv_sub_profile_count)
        val tvLastUpdate: TextView = view.findViewById(R.id.tv_sub_last_update)
        val tvScenarios: TextView = view.findViewById(R.id.tv_sub_scenarios)
        val switchEnabled: MaterialSwitch = view.findViewById(R.id.switch_sub_enabled)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        
        override fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        override fun onItemClear() {
            itemView.setBackgroundColor(0) // Transparent
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_custom_subscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isLocalProfiles = item.id == AppConfig.LOCAL_PROFILES_SUBSCRIPTION_ID
        holder.tvName.text = if (isLocalProfiles) {
            holder.itemView.context.getString(R.string.local_profiles_subscription_name)
        } else {
            item.name
        }
        holder.tvUrl.text = if (isLocalProfiles) {
            holder.itemView.context.getString(R.string.local_profiles_subscription_summary)
        } else {
            item.url.lineSequence()
                .map { it.substringBefore('|').trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
        holder.tvUrl.setOnLongClickListener {
            if (isLocalProfiles) return@setOnLongClickListener false
            Utils.setClipboard(holder.itemView.context, item.url.replace("|", "\n"))
            holder.itemView.context.toast(R.string.toast_subscription_sources_copied)
            true
        }
        holder.tvProfileCount.text = holder.itemView.context.getString(R.string.subscription_profile_count, item.profileCount)

        if (isLocalProfiles) {
            holder.tvLastUpdate.visibility = View.GONE
            holder.cardView.strokeWidth = 0
            holder.tvScenarios.visibility = View.GONE
            holder.switchEnabled.visibility = View.GONE
            holder.btnDelete.visibility = View.GONE
        } else if (item.lastUpdateFailed && item.enabled) {
            holder.tvLastUpdate.visibility = View.VISIBLE
            holder.tvScenarios.visibility = View.VISIBLE
            holder.switchEnabled.visibility = View.VISIBLE
            holder.btnDelete.visibility = View.VISIBLE
            val redColor = ContextCompat.getColor(holder.itemView.context, android.R.color.holo_red_light)
            holder.tvLastUpdate.text = holder.itemView.context.getString(R.string.sub_update_failed)
            holder.tvLastUpdate.setTextColor(redColor)
            holder.tvLastUpdate.visibility = View.VISIBLE
            holder.cardView.strokeColor = redColor
            holder.cardView.strokeWidth = (holder.itemView.context.resources.displayMetrics.density * 1.5f).toInt()
        } else {
            holder.tvLastUpdate.visibility = View.VISIBLE
            holder.tvScenarios.visibility = View.VISIBLE
            holder.switchEnabled.visibility = View.VISIBLE
            holder.btnDelete.visibility = View.VISIBLE
            holder.cardView.strokeWidth = 0
            holder.tvLastUpdate.setTextColor(holder.tvUrl.textColors)
            if (item.lastUpdated > 0) {
                val dateStr = com.kiktor.v2whitelist.util.Utils.formatTimestamp(item.lastUpdated)
                holder.tvLastUpdate.text = holder.itemView.context.getString(R.string.title_last_update, dateStr)
                holder.tvLastUpdate.visibility = View.VISIBLE
            } else {
                holder.tvLastUpdate.text = holder.itemView.context.getString(R.string.title_last_update_never)
                holder.tvLastUpdate.visibility = View.VISIBLE
            }
        }

        val context = holder.itemView.context
        val scenarios = item.targetScenarios
        if (scenarios.isEmpty()) {
            holder.tvScenarios.text = context.getString(R.string.sub_scenario_universal)
        } else {
            val scenarioLabels = scenarios.map { scenarioKey ->
                when (scenarioKey) {
                    com.kiktor.v2whitelist.handler.SubscriptionHelper.SCENARIO_WHITELIST -> "🛡️ БС"
                    com.kiktor.v2whitelist.handler.SubscriptionHelper.SCENARIO_BLACKLIST -> "🌐 ЧС"
                    com.kiktor.v2whitelist.handler.SubscriptionHelper.SCENARIO_YOUTUBE -> "▶️ YouTube"
                    else -> scenarioKey
                }
            }
            holder.tvScenarios.text = scenarioLabels.joinToString(" • ")
        }
        holder.tvScenarios.visibility = if (isLocalProfiles) View.GONE else View.VISIBLE

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = item.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggle(holder.adapterPosition, isChecked)
        }

        holder.btnDelete.setOnClickListener {
            onDelete(holder.adapterPosition)
        }

        holder.itemView.setOnClickListener {
            onEdit(holder.adapterPosition)
        }
    }

    override fun getItemCount() = items.size

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        onMoveCompleted()
    }

    override fun onItemDismiss(position: Int) {
        // Мы используем кнопку для удаления, свайп отключен по умолчанию
    }
}

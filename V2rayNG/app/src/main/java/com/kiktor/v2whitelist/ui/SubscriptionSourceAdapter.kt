package com.kiktor.v2whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.SubscriptionSourcesManager

class SubscriptionSourceAdapter(
    private val onToggle: (SubscriptionSourcesManager.Source, Boolean) -> Unit,
    private val onRemove: (SubscriptionSourcesManager.Source) -> Unit
) : RecyclerView.Adapter<SubscriptionSourceAdapter.ViewHolder>() {

    private val items = mutableListOf<SubscriptionSourcesManager.Source>()
    private var interactionsEnabled = true

    fun submitItems(newItems: List<SubscriptionSourcesManager.Source>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setInteractionsEnabled(enabled: Boolean) {
        interactionsEnabled = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_subscription_source, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = items[position]
        val context = holder.itemView.context
        val isBuiltin = source.id == SubscriptionSourcesManager.BUILTIN_SOURCE_ID

        holder.name.text = source.name
        holder.url.text = if (isBuiltin) {
            context.getString(R.string.subscription_sources_builtin_summary)
        } else {
            source.url
        }
        holder.summary.text = when {
            source.lastError.isNotBlank() -> source.lastError
            else -> context.getString(R.string.subscription_sources_count, source.subscriptionCount)
        }
        holder.remove.visibility = if (isBuiltin) View.GONE else View.VISIBLE
        holder.remove.isEnabled = interactionsEnabled
        holder.remove.setOnClickListener { onRemove(source) }

        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = source.enabled
        holder.enabled.isEnabled = interactionsEnabled
        holder.enabled.setOnCheckedChangeListener { _, checked -> onToggle(source, checked) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_subscription_source_name)
        val url: TextView = view.findViewById(R.id.tv_subscription_source_url)
        val summary: TextView = view.findViewById(R.id.tv_subscription_source_summary)
        val enabled: MaterialSwitch = view.findViewById(R.id.switch_subscription_source_enabled)
        val remove: ImageButton = view.findViewById(R.id.btn_remove_subscription_source)
    }
}

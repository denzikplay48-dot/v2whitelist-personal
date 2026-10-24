package com.kiktor.v2whitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.dto.ProfileItem

class LocalProfileAdapter(
    private val onConnect: (String) -> Unit,
    private val onShowQr: (ProfileItem) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<LocalProfileAdapter.ViewHolder>() {

    data class ProfileRow(
        val guid: String,
        val profile: ProfileItem,
        val sourceName: String,
        val isLocal: Boolean
    )

    private val profiles = mutableListOf<ProfileRow>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_local_profile_name)
        val address: TextView = view.findViewById(R.id.tv_local_profile_address)
        val origin: TextView = view.findViewById(R.id.tv_local_profile_origin)
        val connect: MaterialButton = view.findViewById(R.id.btn_local_profile_connect)
        val qr: MaterialButton = view.findViewById(R.id.btn_local_profile_qr)
        val delete: MaterialButton = view.findViewById(R.id.btn_local_profile_delete)
    }

    fun submitProfiles(items: List<ProfileRow>) {
        profiles.clear()
        profiles.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = profiles[position]
        val profile = row.profile
        holder.title.text = profile.remarks.ifBlank { profile.server ?: profile.configType.name }
        holder.address.text = listOfNotNull(profile.server, profile.serverPort)
            .filter { it.isNotBlank() }
            .joinToString(":")
            .ifBlank { holder.itemView.context.getString(R.string.local_profile_address_unavailable) }
        holder.origin.text = holder.itemView.context.getString(
            R.string.local_profile_origin_format,
            profile.configType.name,
            row.sourceName
        )
        holder.connect.setOnClickListener { onConnect(row.guid) }
        holder.qr.setOnClickListener { onShowQr(profile) }
        holder.delete.visibility = if (row.isLocal) View.VISIBLE else View.GONE
        holder.delete.setOnClickListener { onDelete(row.guid) }
    }

    override fun getItemCount(): Int = profiles.size
}

package com.secondscreen.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.secondscreen.app.databinding.ItemAppBinding

class AppAdapter(
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query: String = ""

    class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    fun setApps(apps: List<AppEntry>) {
        all = apps
        applyFilter()
    }

    fun filter(newQuery: String) {
        query = newQuery
        applyFilter()
    }

    val itemsShown: Int get() = shown.size

    private fun applyFilter() {
        val q = query.trim()
        shown = if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = shown.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = shown[position]
        holder.binding.appIcon.setImageDrawable(app.icon)
        holder.binding.appLabel.text = app.label
        holder.binding.appPackage.text = app.packageName
        holder.binding.root.setOnClickListener { onClick(app) }
    }
}

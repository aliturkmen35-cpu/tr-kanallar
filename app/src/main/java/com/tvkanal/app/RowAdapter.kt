package com.tvkanal.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Hem kategori hem kanal listesi için basit satır adaptörü. */
class RowAdapter(
    private val onClick: (Int) -> Unit,
    private val onFocus: (Int) -> Unit = {},
) : RecyclerView.Adapter<RowAdapter.Holder>() {

    data class Row(val number: String, val title: String)

    private var rows: List<Row> = emptyList()
    var selected: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.rowNumber)
        val name: TextView = v.findViewById(R.id.rowName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.number.text = row.number
        holder.number.visibility = if (row.number.isEmpty()) View.GONE else View.VISIBLE
        holder.name.text = row.title
        holder.itemView.isSelected = position == selected
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnFocusChangeListener { _, has ->
            if (has) onFocus(holder.bindingAdapterPosition)
        }
    }
}

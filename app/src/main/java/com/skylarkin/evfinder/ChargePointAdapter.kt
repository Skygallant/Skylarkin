package com.skylarkin.evfinder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class ChargePointAdapter(
    private val onTap: (ChargePoint) -> Unit
) : RecyclerView.Adapter<ChargePointAdapter.ChargePointViewHolder>() {

    private val items = mutableListOf<ChargePoint>()

    fun submitList(newItems: List<ChargePoint>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChargePointViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chargepoint, parent, false)
        return ChargePointViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChargePointViewHolder, position: Int) {
        holder.bind(items[position], onTap)
    }

    override fun getItemCount(): Int = items.size

    class ChargePointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val addressText: TextView = itemView.findViewById(R.id.addressText)
        private val metaText: TextView = itemView.findViewById(R.id.metaText)
        private val directionText: TextView = itemView.findViewById(R.id.directionText)
        private val compassImage: ImageView = itemView.findViewById(R.id.compassImage)

        fun bind(item: ChargePoint, onTap: (ChargePoint) -> Unit) {
            nameText.text = item.name
            addressText.text = item.address
            metaText.text = String.format(
                Locale.US,
                "%.1f km | %s | %s",
                item.distanceKm,
                item.usageCost,
                item.accessSummary
            )

            directionText.text = item.directionCode
            compassImage.setImageResource(directionDrawableFor(item.directionCode))

            itemView.setOnClickListener { onTap(item) }
        }

        private fun directionDrawableFor(directionCode: String): Int {
            return when (directionCode.uppercase(Locale.US)) {
                "N" -> R.drawable.compass_n
                "NNE" -> R.drawable.compass_nne
                "NE" -> R.drawable.compass_ne
                "ENE" -> R.drawable.compass_ene
                "E" -> R.drawable.compass_e
                "ESE" -> R.drawable.compass_ese
                "SE" -> R.drawable.compass_se
                "SSE" -> R.drawable.compass_sse
                "S" -> R.drawable.compass_s
                "SSW" -> R.drawable.compass_ssw
                "SW" -> R.drawable.compass_sw
                "WSW" -> R.drawable.compass_wsw
                "W" -> R.drawable.compass_w
                "WNW" -> R.drawable.compass_wnw
                "NW" -> R.drawable.compass_nw
                "NNW" -> R.drawable.compass_nnw
                else -> R.drawable.compass_n
            }
        }
    }
}

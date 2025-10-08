package com.example.chatapp.mozgi.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.mozgi.data.TestCategory

class CategoriesAdapter(
    private val onItemClick: (TestCategory) -> Unit
) : RecyclerView.Adapter<CategoriesAdapter.ViewHolder>() {

    private var categories = listOf<TestCategory>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.ivCategoryIcon)
        val name: TextView = itemView.findViewById(R.id.tvCategoryName)
        val description: TextView = itemView.findViewById(R.id.tvCategoryDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]

        holder.icon.setImageResource(category.icon)
        holder.name.text = category.name
        holder.description.text = category.description

        holder.itemView.setOnClickListener {
            onItemClick(category)
        }
    }

    override fun getItemCount(): Int = categories.size

    fun submitList(newCategories: List<TestCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }
}
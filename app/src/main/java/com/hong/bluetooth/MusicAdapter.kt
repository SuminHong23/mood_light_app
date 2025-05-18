package com.hong.bluetooth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MusicAdapter(
    private val items: List<Uri>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    inner class MusicViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView) {
        init {
            textView.setOnClickListener {
                onItemClick(adapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val textView = TextView(parent.context).apply {
            textSize = 20f
            setPadding(20, 20, 20, 20)
        }
        return MusicViewHolder(textView)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val uri = items[position]
        val context = holder.textView.context
        val fileName = getFileNameFromUri(context, uri)
        holder.textView.text = fileName ?: "알 수 없는 파일"
    }

    override fun getItemCount() = items.size

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }
}

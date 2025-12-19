package com.example.chatapp.step

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import com.example.chatapp.R
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight

class CustomMarkerView(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {
    private val tvValue: TextView = findViewById(R.id.tv_marker_value)

    private val widthDp = 70f
    private val heightDp = 35f

    init {
        // Конвертируем dp в пиксели
        val metrics = context.resources.displayMetrics
        val widthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp, metrics).toInt()
        val heightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp, metrics).toInt()

        // Устанавливаем размеры через layoutParams
        layoutParams = LayoutParams(widthPx, heightPx)

        // Устанавливаем смещение через setOffset()
        setOffset(-widthPx / 2f, (-heightPx).toFloat())
    }


    override fun refreshContent(entry: Entry?, highlight: Highlight?) {
        tvValue.text = String.format("%.0f шагов", entry?.y ?: 0f)
        super.refreshContent(entry, highlight)
    }
}


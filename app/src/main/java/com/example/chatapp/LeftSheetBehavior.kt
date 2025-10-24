package com.example.chatapp.location

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

class LeftSheetBehavior : BottomSheetBehavior<View> {

    constructor() : super()
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    private var sheetWidth = 0

    init {
        isHideable = true
        skipCollapsed = true
        state = STATE_HIDDEN
    }

    override fun onLayoutChild(parent: CoordinatorLayout, child: View, layoutDirection: Int): Boolean {
        sheetWidth = child.measuredWidth
        val height = parent.height

        // Размещаем view за левой границей экрана
        child.layout(-sheetWidth, 0, 0, height)

        return true
    }

    override fun setState(state: Int) {
        val previousState = this.state
        super.setState(state)

        if (state != previousState) {
            when (state) {
                STATE_EXPANDED -> {
                    bottomSheet?.animate()
                        ?.translationX(sheetWidth.toFloat())
                        ?.setDuration(300)
                        ?.start()
                }
                STATE_HIDDEN, STATE_COLLAPSED -> {
                    bottomSheet?.animate()
                        ?.translationX(0f)
                        ?.setDuration(300)
                        ?.start()
                }
            }
        }
    }


    private val bottomSheet: View?
        get() = try {
            val field = BottomSheetBehavior::class.java.getDeclaredField("viewRef")
            field.isAccessible = true
            val viewRef = field.get(this) as? java.lang.ref.WeakReference<View>
            viewRef?.get()
        } catch (e: Exception) {
            null
        }
}
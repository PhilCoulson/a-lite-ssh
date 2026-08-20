package com.alite.ssh

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

fun showCenteredSnackbar(
    root: View,
    message: CharSequence,
    actionText: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    val bar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
    bar.animationMode = BaseTransientBottomBar.ANIMATION_MODE_FADE
    val params = bar.view.layoutParams
    if (params is FrameLayout.LayoutParams) {
        params.gravity = Gravity.CENTER
        bar.view.layoutParams = params
    }
    if (actionText != null && onAction != null) {
        bar.setAction(actionText) { onAction() }
    }
    bar.show()
}

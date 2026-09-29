package dev.lindroid

import android.view.View
import android.widget.TextView

// The screens refresh on a timer; touching views only on change keeps the UI idle (cheaper,
// and lets UI automation see a settled screen).

fun TextView.setTextIfChanged(s: CharSequence) {
    if (text.toString() != s.toString()) text = s
}

fun View.setVisibleIfChanged(visible: Boolean) {
    val v = if (visible) View.VISIBLE else View.GONE
    if (visibility != v) visibility = v
}

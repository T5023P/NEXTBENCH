package com.nextbench.core.common

import java.text.NumberFormat
import java.util.Locale

fun formatRelativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
            "${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${
                cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.SHORT, Locale.getDefault())
            }"
        }
    }
}

fun formatRupees(amount: Int): String {
    val fmt = NumberFormat.getNumberInstance(Locale("en", "IN"))
    return "₹${fmt.format(amount)}"
}

package app.vidro.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 10) return String.format(Locale.US, "%.1f KB", kb)
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 10) return String.format(Locale.US, "%.1f MB", mb)
    if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

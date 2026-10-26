package app.vidro.engine

import android.content.Intent

object ExternalLinks {
    private val urlInText = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun urlFrom(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> {
                if (intent.type != null && intent.type != "text/plain") return null
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                urlInText.find(text)?.value
            }
            else -> null
        } ?: return null
        val cleaned = raw.trim().trimEnd('.', ',', ';', ')', ']', '»', '"', '\'')
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return null
        return cleaned
    }
}

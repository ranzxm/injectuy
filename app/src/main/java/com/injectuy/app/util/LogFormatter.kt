package com.injectuy.app.util

import android.graphics.Color
import android.os.Build
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object LogFormatter {

    fun format(rawText: String): CharSequence {
        // Cek jika teks mengandung HTML tags (<font>, <p>, <br>, <b>, dll)
        if (rawText.contains("<") && rawText.contains(">")) {
            val htmlSpanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(rawText, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(rawText)
            }
            return trimTrailingWhitespace(htmlSpanned)
        }

        val ssb = SpannableStringBuilder(rawText)

        // Highlight HTTP Ping Latency: (XXms)
        val pingPattern = Pattern.compile("HTTP Ping 200 OK \\((\\d+)ms\\)")
        val matcher = pingPattern.matcher(rawText)

        if (matcher.find()) {
            val latency = matcher.group(1)?.toIntOrNull() ?: 0
            val startIdx = matcher.start(1) - 1
            val endIdx = matcher.end(1) + 3

            val color = if (latency < 300) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            ssb.setSpan(ForegroundColorSpan(color), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ssb
        }

        // Highlight keywords status
        highlightWord(ssb, rawText, "Connected", Color.parseColor("#00E676"))
        highlightWord(ssb, rawText, "Auth complete", Color.parseColor("#E0E0E0"))
        highlightWord(ssb, rawText, "Disconnected", Color.parseColor("#FF5252"))

        return ssb
    }

    private fun highlightWord(ssb: SpannableStringBuilder, fullText: String, word: String, color: Int) {
        val idx = fullText.indexOf(word)
        if (idx != -1) {
            ssb.setSpan(ForegroundColorSpan(color), idx, idx + word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun trimTrailingWhitespace(source: CharSequence): CharSequence {
        var i = source.length
        while (--i >= 0 && Character.isWhitespace(source[i])) {
        }
        return source.subSequence(0, i + 1)
    }
}

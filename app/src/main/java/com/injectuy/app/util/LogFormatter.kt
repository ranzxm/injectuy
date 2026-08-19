package com.injectuy.app.util

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object LogFormatter {

    private val ANSI_COLOR_MAP = mapOf(
        "30" to Color.parseColor("#000000"),
        "31" to Color.parseColor("#FF5252"), // Red
        "32" to Color.parseColor("#00E676"), // Green
        "33" to Color.parseColor("#FFD600"), // Yellow
        "34" to Color.parseColor("#448AFF"), // Blue
        "35" to Color.parseColor("#E040FB"), // Magenta / Pink
        "36" to Color.parseColor("#18FFFF"), // Cyan
        "37" to Color.parseColor("#FFFFFF"),
        "91" to Color.parseColor("#FF5252"),
        "92" to Color.parseColor("#00E676"),
        "93" to Color.parseColor("#FFD600"),
        "95" to Color.parseColor("#FF4081"),
        "96" to Color.parseColor("#00E5FF")
    )

    fun format(rawText: String): CharSequence {
        val ssb = SpannableStringBuilder()
        val lines = rawText.split("\n")

        for (line in lines) {
            val formattedLine = formatLine(line)
            ssb.append(formattedLine)
            ssb.append("\n")
        }

        return ssb
    }

    private fun formatLine(line: String): CharSequence {
        val ssb = SpannableStringBuilder()

        // Highlight HTTP Ping Latency: (XXms)
        val pingPattern = Pattern.compile("HTTP Ping 200 OK \\((\\d+)ms\\)")
        val matcher = pingPattern.matcher(line)

        if (matcher.find()) {
            val latency = matcher.group(1)?.toIntOrNull() ?: 0
            val startIdx = matcher.start(1) - 1
            val endIdx = matcher.end(1) + 3

            ssb.append(line)
            val color = if (latency < 300) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            ssb.setSpan(ForegroundColorSpan(color), startIdx, endIdx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ssb
        }

        // Parse ANSI codes if present (\u001B[31m etc)
        var cleanLine = line
        if (cleanLine.contains("\u001B[")) {
            val ansiRegex = Pattern.compile("\u001B\\[(\\d+)m")
            val ansiMatcher = ansiRegex.matcher(cleanLine)
            val sbClean = StringBuffer()
            while (ansiMatcher.find()) {
                ansiMatcher.appendReplacement(sbClean, "")
            }
            ansiMatcher.appendTail(sbClean)
            cleanLine = sbClean.toString()
        }

        // Highlight keywords in SSH/Server banners
        ssb.append(cleanLine)
        highlightWord(ssb, cleanLine, "WELCOME", Color.parseColor("#FFD600"))
        highlightWord(ssb, cleanLine, "TERM OF SERVICE", Color.parseColor("#FF5252"))
        highlightWord(ssb, cleanLine, "NO SPAM", Color.parseColor("#00E5FF"))
        highlightWord(ssb, cleanLine, "NO DDOS", Color.parseColor("#00E5FF"))
        highlightWord(ssb, cleanLine, "NO HACKING AND CARDING", Color.parseColor("#00E5FF"))
        highlightWord(ssb, cleanLine, "NO TORRENT!!", Color.parseColor("#FF4081"))
        highlightWord(ssb, cleanLine, "NO MULTI LOGIN!!", Color.parseColor("#FF4081"))
        highlightWord(ssb, cleanLine, "Connected", Color.parseColor("#00E676"))
        highlightWord(ssb, cleanLine, "Auth complete", Color.parseColor("#E0E0E0"))

        return ssb
    }

    private fun highlightWord(ssb: SpannableStringBuilder, fullText: String, word: String, color: Int) {
        val idx = fullText.indexOf(word)
        if (idx != -1) {
            ssb.setSpan(ForegroundColorSpan(color), idx, idx + word.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

package com.rokid.hud.phone

import android.app.Notification

data class ParsedGoogleMapsStep(
    val instruction: String,
    val maneuver: String,
    val distanceMeters: Double
)

object GoogleMapsNotificationParser {

    private val etaOnlyRegex = Regex("""^(\d{1,2}:\d{2}|\d+\s*(min|mins|minutes|phút))$""", RegexOption.IGNORE_CASE)
    private val distanceOnlyRegex = Regex(
        """^\d+(?:[.,]\d+)?\s*(km|kilometers?|m|meters?|mi|miles?|ft|feet)$""",
        RegexOption.IGNORE_CASE
    )

    private val englishDistanceRegex = Regex(
        """\b(?:in\s+)?(\d+(?:[.,]\d+)?)\s*(km|kilometers?|m|meters?|mi|miles?|ft|feet)\b""",
        RegexOption.IGNORE_CASE
    )
    private val vietnameseDistanceRegex = Regex(
        """\b(?:sau|cách|còn\s+cách)\s+(\d+(?:[.,]\d+)?)\s*(km|m|dặm|mi|ft)\b""",
        RegexOption.IGNORE_CASE
    )
    private val leadingDistanceRegexes = listOf(
        Regex("""^in\s+(\d+(?:[.,]\d+)?)\s*(km|kilometers?|m|meters?|mi|miles?|ft|feet)\s*[,\-–:]?\s*(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(\d+(?:[.,]\d+)?)\s*(km|kilometers?|m|meters?|mi|miles?|ft|feet)\s*[,\-–:]\s*(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^sau\s+(\d+(?:[.,]\d+)?)\s*(km|m|dặm|mi|ft)\s*[,\-–:]?\s*(.+)$""", RegexOption.IGNORE_CASE)
    )

    fun parse(
        title: String?,
        text: String?,
        bigText: String? = null,
        subText: String? = null
    ): ParsedGoogleMapsStep? {
        val normalizedTitle = title?.let(::normalize)
        val normalizedText = text?.let(::normalize)
        val normalizedBigText = bigText?.let(::normalize)
        val normalizedSubText = subText?.let(::normalize)

        val specialCase = parseDistanceAndStreetPattern(
            normalizedTitle,
            listOf(normalizedText, normalizedBigText, normalizedSubText)
        )
        if (specialCase != null) return specialCase

        val candidates = listOf(normalizedTitle, normalizedBigText, normalizedText, normalizedSubText)
            .mapNotNull { it?.let(::normalize) }
            .filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null

        val instructionCandidate = candidates.firstOrNull(::looksLikeInstruction)
            ?: candidates.firstOrNull { parseDistanceMeters(it) != null && it.length > 8 }
            ?: return null

        val leading = parseLeadingDistanceInstruction(instructionCandidate)
        val rawInstruction = leading?.second ?: instructionCandidate
        val instruction = cleanInstruction(rawInstruction)
        if (!looksLikeInstruction(instruction)) return null

        val distanceMeters = leading?.first
            ?: candidates.asSequence().mapNotNull(::parseDistanceMeters).firstOrNull()
            ?: -1.0

        return ParsedGoogleMapsStep(
            instruction = instruction,
            maneuver = detectManeuver(candidates, instruction),
            distanceMeters = distanceMeters
        )
    }

    fun parse(notification: Notification): ParsedGoogleMapsStep? {
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let(::normalize)
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let(::normalize)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::normalize)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::normalize)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let(::normalize)
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.let(::normalize)
        val ticker = notification.tickerText?.toString()?.let(::normalize)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.let(::normalize) }
            ?: emptyList()

        val specialCase = parseDistanceAndStreetPattern(
            title,
            listOf(text, bigText, titleBig) + textLines + listOf(subText, summaryText, ticker)
        )
        if (specialCase != null) return specialCase

        val candidates = buildList {
            add(title)
            add(titleBig)
            add(text)
            add(bigText)
            add(subText)
            add(summaryText)
            add(ticker)
            addAll(textLines)
        }
            .mapNotNull { it?.let(::normalize) }
            .filter { it.isNotBlank() }
            .distinct()

        return parseCandidates(candidates, allowBestEffort = true)
    }

    fun parseTexts(values: List<String>): ParsedGoogleMapsStep? {
        val candidates = values
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()
        return parseCandidates(candidates, allowBestEffort = true)
    }

    fun parseBestEffort(notification: Notification): ParsedGoogleMapsStep? {
        val extras = notification.extras ?: return null
        val candidates = buildList {
            add(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString())
            add(notification.tickerText?.toString())
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it?.toString()) }
        }
            .mapNotNull { it?.let(::normalize) }
            .filter { it.isNotBlank() }
            .distinct()

        return parseCandidates(candidates, allowBestEffort = true)
    }

    private fun parseCandidates(candidates: List<String>, allowBestEffort: Boolean): ParsedGoogleMapsStep? {
        if (candidates.isEmpty()) return null

        val instructionCandidate = run {
            val bestMatch = candidates.firstOrNull(::looksLikeInstruction)
                ?: candidates.firstOrNull { parseLeadingDistanceInstruction(it) != null }
                ?: candidates.firstOrNull { parseDistanceMeters(it) != null && looksUsefulFallback(it) }
                ?: if (allowBestEffort) candidates.firstOrNull(::looksUsefulFallback) else null
            bestMatch ?: return null
        }

        val leading = parseLeadingDistanceInstruction(instructionCandidate)
        val rawInstruction = leading?.second ?: instructionCandidate
        val instruction = cleanInstruction(rawInstruction)
        if (!looksLikeInstruction(instruction) && !allowBestEffort) return null
        if (instruction.isBlank()) return null

        val distanceMeters = leading?.first
            ?: candidates.asSequence().mapNotNull(::parseDistanceMeters).firstOrNull()
            ?: -1.0

        return ParsedGoogleMapsStep(
            instruction = instruction,
            maneuver = detectManeuver(candidates, instruction),
            distanceMeters = distanceMeters
        )
    }

    private fun parseDistanceAndStreetPattern(
        title: String?,
        otherCandidates: List<String?>
    ): ParsedGoogleMapsStep? {
        val titleValue = title?.takeIf { it.isNotBlank() } ?: return null
        val distanceMeters = parseDistanceMeters(titleValue) ?: return null
        if (!isDistanceOnly(titleValue)) return null

        val instruction = otherCandidates
            .filterNotNull()
            .map(::cleanInstruction)
            .firstOrNull { isUsefulInstructionCandidate(it) }
            ?: return null

        val candidates = buildList {
            add(titleValue)
            add(instruction)
            addAll(otherCandidates.filterNotNull())
        }

        return ParsedGoogleMapsStep(
            instruction = instruction,
            maneuver = detectManeuver(candidates, instruction),
            distanceMeters = distanceMeters
        )
    }

    private fun normalize(value: String): String = value
        .replace('\n', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun cleanInstruction(value: String): String = normalize(
        value
            .replace(Regex("""^[•·\-–:,\s]+"""), "")
            .replace(Regex("""\s+[•·]\s+.*$"""), "")
    )

    private fun looksLikeInstruction(value: String): Boolean {
        val lower = value.lowercase()
        if (lower.isBlank()) return false
        return listOf(
            "turn left", "turn right", "slight left", "slight right", "sharp left", "sharp right",
            "continue", "keep left", "keep right", "merge", "exit", "head", "arrive",
            "take the", "take exit", "continue onto", "toward", "towards",
            "destination", "roundabout", "fork", "u-turn", "uturn",
            "rẽ trái", "rẽ phải", "đi thẳng", "tiếp tục", "nhập", "lối ra", "ra khỏi",
            "quay đầu", "đến đích", "vòng xuyến", "giữ bên trái", "giữ bên phải",
            "chếch trái", "chếch phải", "đi về phía", "đi theo", "theo lối"
        ).any { lower.contains(it) }
    }

    private fun looksUsefulFallback(value: String): Boolean {
        val normalized = cleanInstruction(value)
        if (normalized.length < 4) return false
        if (etaOnlyRegex.matches(normalized)) return false
        if (normalized.equals("Google Maps", true)) return false
        if (normalized.equals("Maps", true)) return false
        return normalized.any { it.isLetter() }
    }

    private fun isDistanceOnly(value: String): Boolean {
        val normalized = cleanInstruction(value)
        return distanceOnlyRegex.matches(normalized)
    }

    private fun isUsefulInstructionCandidate(value: String): Boolean {
        if (value.isBlank()) return false
        if (etaOnlyRegex.matches(value)) return false
        if (isDistanceOnly(value)) return false
        return looksLikeInstruction(value) || looksUsefulFallback(value)
    }

    private fun parseLeadingDistanceInstruction(value: String): Pair<Double, String>? {
        for (regex in leadingDistanceRegexes) {
            val match = regex.find(value) ?: continue
            val distance = toMeters(match.groupValues[1], match.groupValues[2]) ?: continue
            val instruction = match.groupValues[3].trim()
            if (instruction.isNotBlank()) return distance to instruction
        }
        return null
    }

    private fun parseDistanceMeters(value: String): Double? {
        val regexes = listOf(englishDistanceRegex, vietnameseDistanceRegex)
        for (regex in regexes) {
            val match = regex.find(value) ?: continue
            return toMeters(match.groupValues[1], match.groupValues[2])
        }

        val lower = value.lowercase()
        return when {
            lower.contains("half mile") || lower.contains("nửa dặm") -> 804.672
            lower.contains("quarter mile") -> 402.336
            else -> null
        }
    }

    private fun toMeters(number: String, unit: String): Double? {
        val value = number.replace(',', '.').toDoubleOrNull() ?: return null
        return when (unit.lowercase()) {
            "m", "meter", "meters" -> value
            "km", "kilometer", "kilometers" -> value * 1000.0
            "ft", "feet" -> value * 0.3048
            "mi", "mile", "miles", "dặm" -> value * 1609.344
            else -> null
        }
    }

    private fun detectManeuver(candidates: List<String>, instruction: String): String {
        val searchSpace = buildList {
            add(instruction)
            addAll(candidates)
        }.joinToString(" ").lowercase()

        return when {
            searchSpace.contains("arrive") || searchSpace.contains("destination") || searchSpace.contains("đến đích") -> "arrive"
            searchSpace.contains("u-turn") || searchSpace.contains("uturn") || searchSpace.contains("quay đầu") ||
                searchSpace.contains("↩") || searchSpace.contains("⤴") || searchSpace.contains("hairpin") -> "uturn"
            searchSpace.contains("roundabout") || searchSpace.contains("vòng xuyến") -> "fork"
            searchSpace.contains("merge") || searchSpace.contains("nhập") -> "merge"
            searchSpace.contains("exit") || searchSpace.contains("take exit") || searchSpace.contains("lối ra") || searchSpace.contains("ra khỏi") ||
                searchSpace.contains("ramp") -> "ramp"
            searchSpace.contains("fork") -> "fork"
            searchSpace.contains("depart") || searchSpace.contains("head") || searchSpace.contains("khởi hành") -> "depart"

            searchSpace.contains("sharp left") || searchSpace.contains("hard left") || searchSpace.contains("↖") || searchSpace.contains("⬉") -> "left"
            searchSpace.contains("sharp right") || searchSpace.contains("hard right") || searchSpace.contains("↗") || searchSpace.contains("⬈") -> "right"
            searchSpace.contains("slight left") || searchSpace.contains("keep left") || searchSpace.contains("bear left") ||
                searchSpace.contains("chếch trái") || searchSpace.contains("giữ bên trái") -> "left"
            searchSpace.contains("slight right") || searchSpace.contains("keep right") || searchSpace.contains("bear right") ||
                searchSpace.contains("chếch phải") || searchSpace.contains("giữ bên phải") -> "right"

            searchSpace.contains("turn left") || searchSpace.contains("left turn") || searchSpace.contains("rẽ trái") ||
                searchSpace.contains("trái") || searchSpace.contains("⬅") || searchSpace.contains("←") -> "left"
            searchSpace.contains("turn right") || searchSpace.contains("right turn") || searchSpace.contains("rẽ phải") ||
                searchSpace.contains("phải") || searchSpace.contains("➡") || searchSpace.contains("→") -> "right"
            searchSpace.contains("straight") || searchSpace.contains("continue") || searchSpace.contains("đi thẳng") ||
                searchSpace.contains("tiếp tục") || searchSpace.contains("↑") || searchSpace.contains("⬆") -> "straight"
            else -> "straight"
        }
    }
}
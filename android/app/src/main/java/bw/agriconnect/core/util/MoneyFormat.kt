package bw.agriconnect.core.util

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Botswana pula formatting. The app is single-currency (BWP) per the brief. */
object MoneyFormat {
    private val format: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "BW")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    /** "P 1 234.50" — the 'P' is the pula symbol used in Botswana. */
    fun pula(amount: Double): String = "P ${format.format(amount)}"

    fun pulaCompact(amount: Double): String = when {
        amount >= 1_000_000 -> "P ${(amount / 1_000_000).format1()}M"
        amount >= 1_000 -> "P ${(amount / 1_000).format1()}k"
        else -> "P ${format.format(amount)}"
    }

    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)
}

/** Date helpers. All dates are stored/transmitted as ISO-8601 strings. */
object DateFormatters {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val dayMonthShort = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK)

    fun today(): String = LocalDate.now().toString()

    fun formatDate(isoDate: String?): String = try {
        LocalDate.parse(isoDate).format(dayMonth)
    } catch (_: Exception) {
        isoDate ?: "—"
    }

    fun formatDateShort(isoDate: String?): String = try {
        LocalDate.parse(isoDate).format(dayMonthShort)
    } catch (_: Exception) {
        isoDate ?: "—"
    }

    fun formatMonthYear(period: String?): String = try {
        // Server periods are "YYYY-MM".
        LocalDate.parse("$period-01").format(monthYear)
    } catch (_: Exception) {
        period ?: "—"
    }

    /** "3 hours ago", "Yesterday", or a date — used on activity feeds. */
    fun relative(isoTimestamp: String?): String {
        val instant = parseInstant(isoTimestamp) ?: return isoTimestamp ?: "—"
        val minutes = java.time.Duration.between(instant, Instant.now()).toMinutes()
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            minutes < 60 * 48 -> "Yesterday"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} days ago"
            else -> formatDate(isoTimestamp?.take(10))
        }
    }

    /** "3 hours ago" from a local epoch-millis stamp (used by the offline caches). */
    fun relativeMillis(millis: Long?): String {
        if (millis == null) return "—"
        val minutes = java.time.Duration.between(Instant.ofEpochMilli(millis), Instant.now()).toMinutes()
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            minutes < 60 * 48 -> "Yesterday"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} days ago"
            else -> formatDate(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        }
    }

    fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value, iso).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(value)
            } catch (_: Exception) {
                try {
                    LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /** Millis for the local sync queue's createdAt. */
    fun nowMillis(): Long = System.currentTimeMillis()
}

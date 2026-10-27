package io.github.tiagoaalves.myedenred

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException

/** Parsing for the values the MyEdenred portal returns, which mix numbers and Portuguese-formatted strings. */
internal object EdenredParsing {
	val LISBON: ZoneId = ZoneId.of("Europe/Lisbon")

	/** Accepts 12.5, "12.50", "12,50", "1.234,56 €", "-3,20€". Blank means null. */
	fun money(value: Any?): BigDecimal? {
		when (value) {
			null -> return null
			is BigDecimal -> return value
			is Number -> return BigDecimal(value.toString())
		}
		val cleaned = value.toString()
			.replace("€", "")
			.replace(" ", "")
			.replace(" ", "")
			.trim()
		if (cleaned.isEmpty()) return null
		val normalized = when {
			"," in cleaned && "." in cleaned ->
				if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) cleaned.replace(".", "").replace(',', '.')
				else cleaned.replace(",", "")
			"," in cleaned -> cleaned.replace(',', '.')
			else -> cleaned
		}
		return normalized.toBigDecimalOrNull()
			?: throw EdenredUnexpectedResponseException("Could not read amount '$value'")
	}

	/** What the portal actually sends: 2026-10-23T08:22:15.816+0000 (offset without a colon). */
	private val compactOffsetDateTime: DateTimeFormatter = DateTimeFormatterBuilder()
		.append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
		.appendOffset("+HHmm", "Z")
		.toFormatter()

	private val localDateTimeFormats = listOf(
		DateTimeFormatter.ISO_LOCAL_DATE_TIME,
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
		DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
		DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
		DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
		DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
	)
	private val localDateFormats = listOf(
		DateTimeFormatter.ISO_LOCAL_DATE,
		DateTimeFormatter.ofPattern("dd-MM-yyyy"),
		DateTimeFormatter.ofPattern("dd/MM/yyyy"),
	)

	/**
	 * The portal's date format is not documented, so this tries the plausible ones.
	 * Times without an offset are Lisbon local time; bare dates become Lisbon midnight.
	 */
	fun timestamp(value: String): Instant {
		val text = value.trim()
		runCatching { return OffsetDateTime.parse(text, compactOffsetDateTime).toInstant() }
		runCatching { return OffsetDateTime.parse(text).toInstant() }
		runCatching { return Instant.parse(text) }
		text.toLongOrNull()?.let { epoch -> return if (epoch > 10_000_000_000) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch) }
		for (format in localDateTimeFormats) {
			try {
				return LocalDateTime.parse(text, format).atZone(LISBON).toInstant()
			} catch (_: DateTimeParseException) {
			}
		}
		for (format in localDateFormats) {
			try {
				return LocalDate.parse(text, format).atStartOfDay(LISBON).toInstant()
			} catch (_: DateTimeParseException) {
			}
		}
		throw EdenredUnexpectedResponseException("Could not read date '$value'")
	}

	/** "Compra: MERCADONA           PORTO" -> "Compra: MERCADONA PORTO". The portal pads merchant fields. */
	fun description(value: String?): String = value.orEmpty().trim().replace(Regex("\\s+"), " ")

	fun maskCardNumber(number: String?): String {
		val digits = number?.filter { it.isDigit() }.orEmpty()
		return if (digits.isEmpty()) "****" else "**** ${digits.takeLast(4)}"
	}
}

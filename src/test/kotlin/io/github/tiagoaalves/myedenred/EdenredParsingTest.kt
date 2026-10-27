package io.github.tiagoaalves.myedenred

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EdenredParsingTest {
	@Test
	fun `reads numbers and Portuguese-formatted amounts`() {
		assertEquals(BigDecimal("12.5"), EdenredParsing.money(12.5))
		assertEquals(BigDecimal("12.50"), EdenredParsing.money("12.50"))
		assertEquals(BigDecimal("12.50"), EdenredParsing.money("12,50"))
		assertEquals(BigDecimal("1234.56"), EdenredParsing.money("1.234,56 €"))
		assertEquals(BigDecimal("-3.20"), EdenredParsing.money("-3,20€"))
		assertEquals(BigDecimal("1234.56"), EdenredParsing.money("1,234.56"))
		assertNull(EdenredParsing.money(" "))
		assertNull(EdenredParsing.money(null))
	}

	@Test
	fun `rejects amounts it cannot read instead of guessing`() {
		assertFailsWith<EdenredUnexpectedResponseException> { EdenredParsing.money("abc") }
	}

	@Test
	fun `reads timestamps, treating local times as Lisbon time`() {
		// Lisbon is UTC+1 in summer, UTC+0 in winter.
		// The format the portal really returns (seen 2026-10-25).
		assertEquals(Instant.parse("2026-10-23T08:22:15.816Z"), EdenredParsing.timestamp("2026-10-23T08:22:15.816+0000"))
		assertEquals(Instant.parse("2026-10-23T07:22:15Z"), EdenredParsing.timestamp("2026-10-23T08:22:15+0100"))
		assertEquals(Instant.parse("2026-07-01T11:30:00Z"), EdenredParsing.timestamp("2026-07-01T12:30:00"))
		assertEquals(Instant.parse("2026-01-15T12:30:00Z"), EdenredParsing.timestamp("15-01-2026 12:30:00"))
		assertEquals(Instant.parse("2026-01-15T12:30:00Z"), EdenredParsing.timestamp("15/01/2026 12:30"))
		assertEquals(Instant.parse("2026-07-01T10:00:00Z"), EdenredParsing.timestamp("2026-07-01T12:00:00+02:00"))
		assertEquals(Instant.parse("2026-01-15T00:00:00Z"), EdenredParsing.timestamp("2026-01-15"))
		assertEquals(Instant.parse("2026-01-15T12:30:00Z"), EdenredParsing.timestamp("1768480200000"))
	}

	@Test
	fun `rejects dates it cannot read`() {
		assertFailsWith<EdenredUnexpectedResponseException> { EdenredParsing.timestamp("yesterday") }
	}

	@Test
	fun `collapses the padding in merchant names`() {
		assertEquals("Compra: MERCADONA PORTO", EdenredParsing.description("Compra: MERCADONA           PORTO "))
		assertEquals("", EdenredParsing.description(null))
	}

	@Test
	fun `masks card numbers to the last four digits`() {
		assertEquals("**** 4321", EdenredParsing.maskCardNumber("1234 5678 8765 4321"))
		assertEquals("****", EdenredParsing.maskCardNumber(null))
	}
}

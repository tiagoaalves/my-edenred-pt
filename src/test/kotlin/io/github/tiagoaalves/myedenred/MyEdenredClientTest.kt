package io.github.tiagoaalves.myedenred

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MyEdenredClientTest {
	/** A stand-in portal: answers each path with a canned status and body, and records what it was sent. */
	private class Portal {
		data class Seen(val method: String, val path: String, val query: String?, val authorization: String?, val body: String)

		val answers = mutableMapOf<String, Pair<Int, String>>()
		val seen = mutableListOf<Seen>()
		val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
			createContext("/") { exchange ->
				val path = exchange.requestURI.path.removePrefix("/edenred-customer/v2/")
				seen += Seen(
					exchange.requestMethod,
					path,
					exchange.requestURI.query,
					exchange.requestHeaders.getFirst("Authorization"),
					String(exchange.requestBody.readAllBytes()),
				)
				val (status, body) = answers[path] ?: (404 to "")
				val bytes = body.toByteArray()
				exchange.responseHeaders.add("Content-Type", "application/json")
				exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
				if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
				exchange.close()
			}
			start()
		}
		val baseUrl get() = "http://127.0.0.1:${server.address.port}/edenred-customer/v2/"
	}

	private lateinit var portal: Portal
	private lateinit var client: MyEdenredClient
	private val mapper = ObjectMapper()

	@BeforeTest
	fun start() {
		portal = Portal()
		client = MyEdenredClient(portal.baseUrl)
	}

	@AfterTest
	fun stop() = portal.server.stop(0)

	@Test
	fun `login returns the code challenge, with the username lower-cased`() {
		portal.answers["authenticate/default"] = 200 to """{"data":{"challengeId":4711,"challengeMessage":"Code sent to ***123","resendTries":3}}"""

		val result = client.login(" Me@Example.com ", "pw")

		assertEquals(EdenredLoginResult.CodeRequired("4711", "Code sent to ***123"), result)
		val sent = portal.seen.single()
		assertEquals("POST", sent.method)
		assertEquals("appVersion=1.0&appType=PORTAL&channel=WEB", sent.query)
		assertEquals(mapper.readTree("""{"userId":"me@example.com","password":"pw"}"""), mapper.readTree(sent.body))
	}

	@Test
	fun `login returns a session when no code is asked`() {
		portal.answers["authenticate/default"] = 200 to """{"data":{"token":"tok"}}"""

		assertEquals(EdenredLoginResult.Session("tok"), client.login("me@example.com", "pw"))
	}

	@Test
	fun `wrong password is reported as rejected`() {
		portal.answers["authenticate/default"] = 409 to """{"message":["Invalid"]}"""

		assertFailsWith<EdenredRejectedException> { client.login("me@example.com", "bad") }
	}

	@Test
	fun `verification sends the challenge id as a number and returns the token`() {
		portal.answers["authenticate/default/challenge"] = 200 to """{"data":{"token":"session-token"}}"""

		assertEquals("session-token", client.completeChallenge("me@example.com", "pw", "4711", " 12345 "))
		assertEquals(
			mapper.readTree("""{"userId":"me@example.com","password":"pw","authenticationMfaProcessId":4711,"token":"12345"}"""),
			mapper.readTree(portal.seen.single().body),
		)
	}

	@Test
	fun `reads cards and statement with the raw token as Authorization`() {
		portal.answers["protected/card/list"] = 200 to """{"data":[{"id":991,"number":"5555 4444 3333 2222","status":"ACTIVE"}]}"""
		portal.answers["protected/card/991/accountmovement"] = 200 to """
			{"data":{
			  "account":{"availableBalance":"123,45"},
			  "movementList":[
			    {"transactionDate":"2026-10-20T13:05:00","transactionName":"PINGO DOCE      LISBOA","amount":-8.4,"balance":123.45,
			     "category":{"id":2,"description":"Supermercado"},"mcc":"5411","transactionType":4010},
			    {"transactionDate":"2026-10-01T09:00:00","transactionName":"CARREGAMENTO","amount":"176,00","balance":"131,85"}
			  ]}}
			"""

		val cards = client.cards("session-token")
		val statement = client.statement("session-token", cards.single().id)

		assertEquals(listOf("session-token", "session-token"), portal.seen.map { it.authorization })
		assertEquals("**** 2222", cards.single().maskedNumber)
		assertEquals(BigDecimal("123.45"), statement.availableBalance)
		val (groceries, topUp) = statement.movements
		assertEquals("PINGO DOCE LISBOA", groceries.description)
		assertEquals(BigDecimal("-8.4"), groceries.amount)
		assertEquals(Instant.parse("2026-10-20T12:05:00Z"), groceries.bookedAt)
		assertEquals(EdenredCategory(2, "Supermercado"), groceries.category)
		assertEquals("5411", groceries.mcc)
		assertEquals("4010", groceries.transactionType)
		assertEquals(BigDecimal("176.00"), topUp.amount)
		assertNull(topUp.category)
		assertNotEquals(groceries.id, topUp.id)
		assertEquals(32, groceries.id.length)
	}

	@Test
	fun `the same movement always gets the same id`() {
		portal.answers["protected/card/1/accountmovement"] =
			200 to """{"data":{"account":{},"movementList":[{"transactionDate":"2026-10-20","transactionName":"X","amount":1,"balance":2}]}}"""

		val first = client.statement("t", "1").movements.single().id
		val second = client.statement("t", "1").movements.single().id

		assertEquals(first, second)
		// Pinned: changing how ids are built would duplicate every movement for anyone storing them.
		assertEquals("6c8d29564b0b1e4aff25732527159641", first)
	}

	@Test
	fun `a session never prints its token`() {
		assertEquals("Session(token=<hidden>)", EdenredLoginResult.Session("secret").toString())
	}

	@Test
	fun `an expired session is reported as such`() {
		portal.answers["protected/card/list"] = 401 to ""

		assertFailsWith<EdenredSessionExpiredException> { client.cards("old") }
	}

	@Test
	fun `an unreachable portal is an EdenredException`() {
		portal.server.stop(0)

		assertFailsWith<EdenredException> { client.cards("t") }
	}
}

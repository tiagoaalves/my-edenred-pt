package io.github.tiagoaalves.myedenred

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Read-only client for the API behind the MyEdenred Portugal web portal (www.myedenred.pt).
 *
 * Unofficial and not affiliated with Edenred. Logging in takes two steps: [login] sends the password and usually
 * returns [EdenredLoginResult.CodeRequired], because Edenred texts or emails a 5-digit code; [completeChallenge]
 * trades that code for a session token. Sessions are short-lived, so expect [EdenredSessionExpiredException] and
 * log in again. The client keeps no state: hold on to the token yourself.
 *
 * Thread-safe; share one instance.
 */
public class MyEdenredClient @JvmOverloads constructor(
	baseUrl: String = DEFAULT_BASE_URL,
	private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
	private val requestTimeout: Duration = Duration.ofSeconds(30),
) {
	private val baseUri = URI.create(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
	private val json = ObjectMapper()

	/**
	 * Sends the username and password. MyEdenred treats the username as case-insensitive and the portal lower-cases
	 * it, so this does too.
	 */
	public fun login(username: String, password: String): EdenredLoginResult {
		val response = post("authenticate/default", mapOf("userId" to normalize(username), "password" to password))
		if (response.status in REJECTED) throw EdenredRejectedException("MyEdenred rejected the username or password.")
		val data = response.requireOk("login").path("data")

		data.textOrNull("token")?.let { return EdenredLoginResult.Session(it) }
		val challengeId = data.textOrNull("challengeId")
			?: throw EdenredUnexpectedResponseException("Login response had neither a session nor a verification challenge.")
		return EdenredLoginResult.CodeRequired(challengeId, data.textOrNull("challengeMessage"))
	}

	/** Finishes a login with the code Edenred sent. Returns the session token. */
	public fun completeChallenge(username: String, password: String, challengeId: String, code: String): String {
		val response = post(
			"authenticate/default/challenge",
			mapOf(
				"userId" to normalize(username),
				"password" to password,
				"authenticationMfaProcessId" to (challengeId.toLongOrNull() ?: challengeId),
				"token" to code.trim(),
			),
		)
		if (response.status in REJECTED) throw EdenredRejectedException("MyEdenred rejected the verification code.")
		return response.requireOk("verification").path("data").textOrNull("token")
			?: throw EdenredUnexpectedResponseException("Verification response did not include a session.")
	}

	/** The cards on the account. */
	public fun cards(token: String): List<EdenredCard> {
		val data = get("protected/card/list", token).path("data")
		if (!data.isArray) throw EdenredUnexpectedResponseException("Card list was not a list.")
		return data.mapNotNull { card ->
			val id = card.textOrNull("id") ?: return@mapNotNull null
			EdenredCard(
				id = id,
				maskedNumber = EdenredParsing.maskCardNumber(card.textOrNull("number")),
				status = card.textOrNull("status"),
			)
		}
	}

	/** A card's available balance and recent movements (the portal returns roughly the last five months). */
	public fun statement(token: String, cardId: String): EdenredCardStatement {
		val data = get("protected/card/$cardId/accountmovement", token).path("data")
		val balance = data.path("account").moneyOrNull("availableBalance")
		val list = data.path("movementList")
		val movements = if (list.isArray) list.map(::movement) else emptyList()
		return EdenredCardStatement(balance, movements)
	}

	private fun movement(node: JsonNode): EdenredMovement {
		val date = node.textOrNull("transactionDate")
			?: throw EdenredUnexpectedResponseException("Movement without transactionDate: $node")
		val name = node.textOrNull("transactionName").orEmpty()
		val amount = node.moneyOrNull("amount")
			?: throw EdenredUnexpectedResponseException("Movement without amount: $node")
		val ownId = ID_FIELDS.firstNotNullOfOrNull { node.textOrNull(it) }
		val category = node.path("category").takeIf { it.isObject && it.path("id").canConvertToInt() }
		return EdenredMovement(
			id = ownId ?: hash(date, name, node.path("amount").toString(), node.path("balance").toString()),
			bookedAt = EdenredParsing.timestamp(date),
			description = EdenredParsing.description(name),
			amount = amount,
			balanceAfter = node.moneyOrNull("balance"),
			category = category?.let { EdenredCategory(it.path("id").asInt(), it.textOrNull("description")) },
			mcc = node.textOrNull("mcc"),
			transactionType = node.textOrNull("transactionType"),
			raw = json.writeValueAsString(node),
		)
	}

	// --- HTTP plumbing ------------------------------------------------------

	private data class Response(val status: Int, val body: JsonNode)

	private fun post(path: String, body: Map<String, Any>): Response =
		send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))))

	private fun get(path: String, token: String): JsonNode {
		val response = send(request(path).header("Authorization", token).GET())
		if (response.status == 401 || response.status == 403) throw EdenredSessionExpiredException()
		return response.requireOk(path)
	}

	private fun request(path: String): HttpRequest.Builder =
		HttpRequest.newBuilder(baseUri.resolve("$path?$COMMON_QUERY"))
			.timeout(requestTimeout)
			.header("Accept", "application/json")

	private fun send(request: HttpRequest.Builder): Response {
		val response = try {
			httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
		} catch (e: IOException) {
			throw EdenredException("Could not reach MyEdenred: ${e.message}", e)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw EdenredException("Interrupted while calling MyEdenred", e)
		}
		return Response(response.statusCode(), parse(response.body()))
	}

	private fun Response.requireOk(what: String): JsonNode {
		if (status !in 200..299) throw EdenredUnexpectedResponseException("MyEdenred $what failed with HTTP $status.")
		return body
	}

	private fun parse(bytes: ByteArray): JsonNode =
		if (bytes.isEmpty()) json.missingNode()
		else try {
			json.readTree(bytes) ?: json.missingNode()
		} catch (_: JacksonException) {
			json.missingNode()
		}

	private fun JsonNode.textOrNull(field: String): String? {
		val value = get(field) ?: return null
		if (value.isNull || value.isMissingNode || value.isContainerNode) return null
		return value.asText().trim().ifEmpty { null }
	}

	private fun JsonNode.moneyOrNull(field: String): BigDecimal? {
		val value = get(field) ?: return null
		return when {
			value.isNull || value.isMissingNode -> null
			value.isNumber -> value.decimalValue()
			else -> EdenredParsing.money(value.asText())
		}
	}

	private fun normalize(username: String) = username.trim().lowercase()

	private fun hash(vararg parts: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest(parts.joinToString("|").toByteArray())
			.joinToString("") { "%02x".format(it) }
			.take(32)

	public companion object {
		public const val DEFAULT_BASE_URL: String = "https://www.myedenred.pt/edenred-customer/v2/"

		private val REJECTED = setOf(400, 401, 403, 409)
		private val ID_FIELDS = listOf("id", "movementId", "transactionId")
		private const val COMMON_QUERY = "appVersion=1.0&appType=PORTAL&channel=WEB"
	}
}

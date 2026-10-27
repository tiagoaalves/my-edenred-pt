package io.github.tiagoaalves.myedenred

import java.math.BigDecimal
import java.time.Instant

/** What the first login step returns. */
public sealed interface EdenredLoginResult {
	/** The portal skipped the verification code and handed over a session. */
	public data class Session(val token: String) : EdenredLoginResult {
		/** Keeps the token out of logs. */
		override fun toString(): String = "Session(token=<hidden>)"
	}

	/** The portal sent a 5-digit code; finish with [MyEdenredClient.completeChallenge]. */
	public data class CodeRequired(val challengeId: String, val hint: String?) : EdenredLoginResult
}

public data class EdenredCard(
	val id: String,
	/** "**** 1234": the full number never leaves the client. */
	val maskedNumber: String,
	val status: String?,
)

public data class EdenredCardStatement(
	val availableBalance: BigDecimal?,
	val movements: List<EdenredMovement>,
)

public data class EdenredMovement(
	/**
	 * Stable id for de-duplication. The portal sends none, so this is a hash of date, name, amount and balance
	 * after: the same movement always gets the same id across calls.
	 */
	val id: String,
	val bookedAt: Instant,
	/** The merchant or top-up text, with the portal's padding collapsed ("Compra: MERCADONA PORTO"). */
	val description: String,
	/** Negative for purchases, positive for top-ups. */
	val amount: BigDecimal,
	val balanceAfter: BigDecimal?,
	/** Edenred's own category, e.g. 1 "Refeição", 2 "Supermercado". */
	val category: EdenredCategory? = null,
	/** Merchant category code, when the portal sends one. */
	val mcc: String? = null,
	/** The portal's transaction type code: 4010 purchase, 4050 top-up. */
	val transactionType: String? = null,
	/** The portal's original record as JSON, for anything this model doesn't cover. */
	val raw: String = "{}",
)

public data class EdenredCategory(val id: Int, val description: String?)

package io.github.tiagoaalves.myedenred

/** Base for everything this client throws: also the portal being unreachable. */
public open class EdenredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Username, password or verification code rejected. */
public class EdenredRejectedException(message: String) : EdenredException(message)

/** The session token no longer works; a new login (and code) is needed. */
public class EdenredSessionExpiredException : EdenredException("MyEdenred session expired. Log in again to get a new code.")

/** The portal answered with something this client does not understand. */
public class EdenredUnexpectedResponseException(message: String, cause: Throwable? = null) : EdenredException(message, cause)

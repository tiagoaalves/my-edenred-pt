# my-edenred-pt

[![](https://jitpack.io/v/tiagoaalves/my-edenred-pt.svg)](https://jitpack.io/#tiagoaalves/my-edenred-pt)

An unofficial, read-only JVM client for **MyEdenred Portugal** ([myedenred.pt](https://www.myedenred.pt)), the portal
for Edenred meal cards (cartão refeição). It logs in, lists your cards, and reads each card's balance and movements.

Written in Kotlin, works from Java too. Needs Java 17+, and its only dependency is Jackson.

Status: early (0.x). It's used daily against the real portal, but the API may still change between minor versions.

> **Not affiliated with Edenred.** This talks to the private API behind the MyEdenred web portal, which is
> undocumented and can change or break without notice. Use it only with your own account and credentials, and check
> that it fits Edenred's terms of use.

## Install

Add JitPack and the dependency (Gradle Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.tiagoaalves:my-edenred-pt:0.1.0")
}
```

<details>
<summary>Maven</summary>

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.tiagoaalves</groupId>
  <artifactId>my-edenred-pt</artifactId>
  <version>0.1.0</version>
</dependency>
```
</details>

## Use

Logging in takes two steps. The password goes first; Edenred then sends a 5-digit code by SMS or email, and the code
gets you a session token.

```kotlin
val client = MyEdenredClient()

val token = when (val result = client.login("me@example.com", password)) {
    is EdenredLoginResult.Session -> result.token // sometimes no code is asked
    is EdenredLoginResult.CodeRequired -> {
        println(result.hint) // e.g. "Code sent to ***123"
        val code = readln()
        client.completeChallenge("me@example.com", password, result.challengeId, code)
    }
}

for (card in client.cards(token)) {
    val statement = client.statement(token, card.id)
    println("${card.maskedNumber}: ${statement.availableBalance} €")
    statement.movements.forEach { println("${it.bookedAt}  ${it.amount}  ${it.description}") }
}
```

From Java:

```java
var client = new MyEdenredClient();
var result = client.login("me@example.com", password);
if (result instanceof EdenredLoginResult.CodeRequired challenge) {
    String token = client.completeChallenge("me@example.com", password, challenge.getChallengeId(), code);
}
```

### What you get

| | |
|---|---|
| `EdenredCard` | `id`, `maskedNumber` (`**** 1234`), `status` |
| `EdenredCardStatement` | `availableBalance`, `movements` (about the last five months) |
| `EdenredMovement` | `id`, `bookedAt`, `description`, `amount` (negative = purchase), `balanceAfter`, `category` (Edenred's own: 1 Refeição, 2 Supermercado), `mcc`, `transactionType` (4010 purchase, 4050 top-up), `raw` (the original JSON) |

The portal gives movements no id, so `id` is a hash of the date, name, amount and balance after. The same movement
always gets the same id, which makes it safe to de-duplicate on when you sync repeatedly.

### Sessions and errors

The client keeps no state: store the token yourself and reuse it until it expires. Sessions are short-lived (in
testing they expired within two to three hours of logging in) and the portal offers no refresh token, so every new
session needs a new code. Plan for a person in the loop: fetch what you need right after logging in, and treat
`EdenredSessionExpiredException` as "ask for a new code", not as an error.

| Exception | When |
|---|---|
| `EdenredRejectedException` | Wrong username, password or code |
| `EdenredSessionExpiredException` | The token stopped working; log in again |
| `EdenredUnexpectedResponseException` | The portal answered with something the client doesn't understand (probably an API change) |
| `EdenredException` | Base class; also thrown when the portal can't be reached |

You can pass your own `java.net.http.HttpClient`, a request timeout, or another base URL (for tests) to the
constructor.

## Credits

The endpoints and the two-step login were first worked out by [taduo/ha-myedenred-pt](https://github.com/taduo/ha-myedenred-pt),
a Home Assistant integration (MIT, now archived). This library was extracted from Timoney, my personal finance app.

## License

[MIT](LICENSE)

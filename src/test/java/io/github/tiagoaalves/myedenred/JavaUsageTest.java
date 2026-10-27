package io.github.tiagoaalves.myedenred;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The README's Java example, so the API stays pleasant to call from Java. */
class JavaUsageTest {
	@Test
	void loginFromJava() throws Exception {
		HttpServer portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		portal.createContext("/", exchange -> {
			byte[] body = "{\"data\":{\"challengeId\":4711,\"challengeMessage\":\"Code sent\"}}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		portal.start();
		try {
			var client = new MyEdenredClient("http://127.0.0.1:" + portal.getAddress().getPort() + "/");
			var result = client.login("me@example.com", "pw");

			var challenge = assertInstanceOf(EdenredLoginResult.CodeRequired.class, result);
			assertEquals("4711", challenge.getChallengeId());
			assertEquals("Code sent", challenge.getHint());
		} finally {
			portal.stop(0);
		}
	}

	@Test
	void defaultsNeedNoArguments() {
		assertEquals("https://www.myedenred.pt/edenred-customer/v2/", MyEdenredClient.DEFAULT_BASE_URL);
		new MyEdenredClient();
	}
}

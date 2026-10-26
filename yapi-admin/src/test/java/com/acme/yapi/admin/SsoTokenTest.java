package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.admin.support.AdminTestBase;
import com.acme.yapi.admin.support.CentralDatabase;
import com.acme.yapi.core.testing.JsonHttp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;

/** Without the local profile the API takes SSO access tokens; roles come from a claim, groups are mapped. */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yapi.admin.catalog.mode=memory",
        "yapi.admin.authorization.mode=memory",
        "yapi.admin.security.roles-claim=groups",
        "yapi.admin.security.group-roles.devops-editors=ROUTE_EDITOR",
        "yapi.admin.security.group-roles.change-board=APPROVER"
})
class SsoTokenTest {

    static final KeyPair KEYS = keys();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path pem = Files.createTempFile("sso", ".pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location", () -> pem.toUri().toString());
        CentralDatabase.register(registry);
        registry.add("spring.flyway.placeholders.dev_admin_api_url", () -> AdminTestBase.ENVIRONMENTS.url("dev"));
        registry.add("spring.flyway.placeholders.test_admin_api_url", () -> AdminTestBase.ENVIRONMENTS.url("test"));
        registry.add("spring.flyway.placeholders.prod_admin_api_url", () -> AdminTestBase.ENVIRONMENTS.url("prod"));
    }

    @LocalServerPort
    int port;

    private JsonHttp.Response me(String token) {
        return new JsonHttp("http://localhost:" + port).send("GET", "/api/v1/me", null,
                token == null ? Map.of() : Map.of("Authorization", "Bearer " + token));
    }

    @Test
    void rolesComeFromTheTokenGroups() throws Exception {
        JsonHttp.Response response = me(token(List.of("devops-editors", "change-board", "unrelated"), 300));

        assertThat(response.status()).as(response.text()).isEqualTo(200);
        JsonNode me = response.json();
        assertThat(me.get("username").stringValue()).isEqualTo("ayse.yilmaz");
        assertThat(me.get("displayName").stringValue()).isEqualTo("Ayşe Yılmaz");
        assertThat(me.get("roles").valueStream().map(JsonNode::stringValue))
                .containsExactly("VIEWER", "ROUTE_EDITOR", "APPROVER");
    }

    @Test
    void tokensWithoutRolesOrValidityAreRefused() throws Exception {
        JsonHttp.Response noRole = me(token(List.of("unrelated"), 300));
        assertThat(noRole.status()).isEqualTo(403);
        assertThat(noRole.json().get("code").stringValue()).isEqualTo("FORBIDDEN");

        JsonHttp.Response expired = me(token(List.of("devops-editors"), -300));
        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.json().get("code").stringValue()).isEqualTo("UNAUTHENTICATED");
        assertThat(me(null).status()).isEqualTo(401);
        assertThat(me("not-a-token").status()).isEqualTo(401);
    }

    @Test
    void openApiAndSwaggerAreOffOutsideLocalAndDev() throws Exception {
        JsonHttp http = new JsonHttp("http://localhost:" + port);
        Map<String, String> signedIn = Map.of("Authorization", "Bearer " + token(List.of("devops-editors"), 300));

        assertThat(http.send("GET", "/v3/api-docs", null, signedIn).status()).isEqualTo(404);
        assertThat(http.send("GET", "/swagger-ui.html", null, signedIn).status()).isEqualTo(404);
    }

    private static String token(List<String> groups, long validSeconds) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("4c2a7e")
                .claim("preferred_username", "ayse.yilmaz")
                .claim("name", "Ayşe Yılmaz")
                .claim("groups", groups)
                .issueTime(Date.from(now.minusSeconds(600)))
                .expirationTime(Date.from(now.plusSeconds(validSeconds)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.acme.yapi.envadmin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.acme.yapi.core.testing.JsonHttp;
import com.acme.yapi.envadmin.testing.RuntimeContainers;

/** PROD: the Admin API refuses test calls; a POST there would create real records. */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.ssl.enabled=false",
        "management.server.port=0",
        "yapi.env-admin.environment=PROD"
})
class TestCallsDisabledTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        RuntimeContainers.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void testCallsAreRefused() {
        JsonHttp.Response response = new JsonHttp("http://localhost:" + port).post("/admin/v1/test", """
                {"maintask": "order-service", "httpMethod": "GET", "pathTemplate": "/x", "sample": {}}
                """);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("code").stringValue()).isEqualTo("TEST_NOT_ALLOWED");
    }
}

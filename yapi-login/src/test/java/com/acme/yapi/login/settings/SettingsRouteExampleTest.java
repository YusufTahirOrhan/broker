package com.acme.yapi.login.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.route.RouteValidator;

/** The README's example routes for the settings pass the same validation as a saved route. */
class SettingsRouteExampleTest {

    @Test
    void theReadmesSettingsRoutesAreValid() {
        RouteValidator.Context context = RouteValidator.Context.of(List.of("Accept-Language", "X-Clientip",
                "X-User-Agent"));

        assertThat(RouteValidator.validate("PUT", "/users/me/settings/layout", Json.parse("""
                {
                  "request": {
                    "fields": [{ "name": "layout", "type": "object", "required": true }],
                    "body": { "mode": "TEMPLATE", "contentType": "application/json", "template": "${request.layout}" }
                  },
                  "response": { "type": "JSON" }
                }
                """), context)).isEmpty();
        assertThat(RouteValidator.validate("GET", "/users/me/settings/layout", Json.parse("""
                {"response": {"type": "JSON"}}
                """), context)).isEmpty();
    }
}

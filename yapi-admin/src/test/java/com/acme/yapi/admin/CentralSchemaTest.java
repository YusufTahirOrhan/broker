package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.acme.yapi.admin.support.AdminTestBase;
import com.acme.yapi.admin.support.CentralDatabase;
import com.acme.yapi.core.json.Json;
import com.acme.yapi.core.testing.CoreFixtures;
import com.acme.yapi.core.testing.OracleDatabase;

import tools.jackson.databind.JsonNode;

/** What the central schema guards by itself, on Oracle: the rules hold even for SQL that bypasses the API. */
class CentralSchemaTest extends AdminTestBase {

    /** The order service and the CRM project whose permission add_order_note borrows. */
    private long orderService() {
        long id = project("order-service", "ORDER", "P-100");
        project("crm", "CRM", "P-300");
        return id;
    }

    @Test
    void theSchemaUsesNoReservedWords() {
        assertThat(CentralDatabase.ORACLE.reservedNames()).isEmpty();
    }

    @Test
    void definitionsLargerThanAVarcharAreStoredAndVersionedWhole() {
        long projectId = orderService();
        String definition = CoreFixtures.largeAddOrderNote(40_000);
        long id = route(projectId, addOrderNote("add_long_note", null).replace(CoreFixtures.ADD_ORDER_NOTE, definition));

        assertThat(get("viewer", "/routes/" + id).json().get("definition")).isEqualTo(Json.parse(definition));
        assertThat(get("viewer", "/routes/" + id + "/history/1").json().at("/snapshot/definition"))
                .isEqualTo(Json.parse(definition));
        String stored = jdbc.sql("SELECT snapshot FROM route_definition_history WHERE definition_id = :id")
                .param("id", id).query(String.class).single();
        assertThat(stored.length()).isGreaterThan(32 * 1024);
    }

    @Test
    void historyCannotBeChangedEvenByTheSchemaOwner() {
        long projectId = orderService();
        route(projectId, addOrderNote("add_order_note", null));

        assertThatThrownBy(() -> jdbc.sql("UPDATE route_definition_history SET action = 'UPDATE'").update())
                .hasMessageContaining("ORA-20001");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM route_definition_history").update())
                .hasMessageContaining("ORA-20001");
    }

    @Test
    void keysOfTheFrontendContractCannotChange() {
        long projectId = orderService();
        long routeId = route(projectId, addOrderNote("add_order_note", null));
        long profileId = profile("log-project");

        assertThatThrownBy(() -> jdbc.sql("UPDATE project SET permission_prefix = 'ORDERS' WHERE id = :id")
                .param("id", projectId).update()).hasMessageContaining("ORA-20002");
        assertThatThrownBy(() -> jdbc.sql("UPDATE route_definition SET subtask = 'other' WHERE id = :id")
                .param("id", routeId).update()).hasMessageContaining("ORA-20002");
        assertThatThrownBy(() -> jdbc.sql("UPDATE response_profile SET name = 'other' WHERE id = :id")
                .param("id", profileId).update()).hasMessageContaining("ORA-20002");
        assertThat(jdbc.sql("UPDATE project SET owner_team = 'x' WHERE id = :id").param("id", projectId).update())
                .isEqualTo(1);
    }

    @Test
    void permissionsCarryTheirProjectsPrefixes() {
        long projectId = orderService();
        long routeId = route(projectId, addOrderNote("add_order_note", null));
        String insert = """
                INSERT INTO route_permission (definition_id, kind, sort_order, code) VALUES (:id, :kind, 9, :code)
                """;

        assertThatThrownBy(() -> jdbc.sql(insert).param("id", routeId).param("kind", "OWN").param("code", "CRM_X")
                .update()).hasMessageContaining("ORA-20003");
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", routeId).param("kind", "FOREIGN")
                .param("code", "HR_X").update()).hasMessageContaining("ORA-20003");
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", routeId).param("kind", "FOREIGN")
                .param("code", "ORDER_X").update()).hasMessageContaining("ORA-20003");
        JsonNode permissions = get("viewer", "/routes/" + routeId).json();
        assertThat(permissions.get("permissions").valueStream().map(JsonNode::stringValue)).containsExactly("ORDER_WRITE");
        assertThat(permissions.get("foreignPermissions").valueStream().map(JsonNode::stringValue))
                .containsExactly("CRM_AGENT");
    }

    @Test
    void oneOpenRequestPerSubjectAndTargetEnvironment() {
        long projectId = orderService();
        long routeId = route(projectId, addOrderNote("add_order_note", null));
        long profileId = profile("log-project");
        String route = """
                INSERT INTO promotion_request (definition_id, version, target_env, status, requested_by)
                VALUES (:id, 1, :target, :status, 'editor')
                """;
        String profile = """
                INSERT INTO promotion_request (profile_id, profile_version, target_env, status, requested_by)
                VALUES (:id, 1, :target, :status, 'editor')
                """;

        jdbc.sql(route).param("id", routeId).param("target", "TEST").param("status", "APPLYING").update();
        assertThatThrownBy(() -> jdbc.sql(route).param("id", routeId).param("target", "TEST")
                .param("status", "PENDING_APPROVAL").update()).hasMessageContaining("UQ_PROMOTION_OPEN");
        jdbc.sql(route).param("id", routeId).param("target", "TEST").param("status", "APPLIED").update();
        jdbc.sql(route).param("id", routeId).param("target", "TEST").param("status", "FAILED").update();
        jdbc.sql(route).param("id", routeId).param("target", "PROD").param("status", "PENDING_APPROVAL").update();

        jdbc.sql(profile).param("id", profileId).param("target", "TEST").param("status", "APPROVED").update();
        assertThatThrownBy(() -> jdbc.sql(profile).param("id", profileId).param("target", "TEST")
                .param("status", "APPLYING").update()).hasMessageContaining("UQ_PROMOTION_OPEN_PROFILE");
        jdbc.sql(profile).param("id", profileId).param("target", "PROD").param("status", "APPLYING").update();
    }

    @Test
    void theApplicationUserCannotRewriteHistoryOrEnvironments() {
        long projectId = orderService();
        route(projectId, addOrderNote("add_order_note", null));
        SingleConnectionDataSource connection = new SingleConnectionDataSource(CentralDatabase.ORACLE.url(),
                CentralDatabase.APP_USER, OracleDatabase.PASSWORD, true);
        connection.setSchema(CentralDatabase.ORACLE.schema());
        JdbcClient application = JdbcClient.create(connection);
        try {
            assertThat(application.sql("SELECT COUNT(*) FROM route_definition_history").query(Long.class).single())
                    .isEqualTo(1);
            assertThatThrownBy(() -> application.sql("DELETE FROM route_definition_history").update())
                    .rootCause().hasMessageContaining("ORA-01031");
            assertThatThrownBy(() -> application.sql("UPDATE environment SET name = 'x'").update())
                    .rootCause().hasMessageContaining("ORA-01031");
            assertThatThrownBy(() -> application.sql("DELETE FROM project").update())
                    .rootCause().hasMessageContaining("ORA-01031");
        } finally {
            connection.destroy();
        }
    }

    @Test
    void searchTreatsWildcardsAsText() {
        project("order-service", "ORDER", "P-100");
        project("crm", "CRM", "P-300");

        assertThat(get("viewer", "/projects?query=_").json()).isEmpty();
        assertThat(get("viewer", "/projects?query=%25").json()).isEmpty();
        assertThat(get("viewer", "/projects?query=R-S").json().valueStream()
                .map(project -> project.get("maintask").stringValue())).containsExactly("order-service");
    }
}

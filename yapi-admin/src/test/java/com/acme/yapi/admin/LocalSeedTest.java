package com.acme.yapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.local.LocalSeed;
import com.acme.yapi.admin.profile.ProfileService;
import com.acme.yapi.admin.project.ProjectRepository;
import com.acme.yapi.admin.project.ProjectService;
import com.acme.yapi.admin.route.RouteService;
import com.acme.yapi.admin.support.AdminTestBase;

import tools.jackson.databind.JsonNode;

/** The sample data a developer's empty DB gets in the {@code local} profile goes through the real services. */
class LocalSeedTest extends AdminTestBase {

    @Autowired
    ProjectService projectService;

    @Autowired
    ProjectRepository projects;

    @Autowired
    ProfileService profiles;

    @Autowired
    RouteService routes;

    @Autowired
    EnvironmentRepository environments;

    @Test
    void anEmptyDatabaseGetsSampleProjectsRoutesAndAProfileOnce() throws Exception {
        LocalSeed seed = new LocalSeed(projectService, projects, profiles, routes, environments);

        seed.run(null);
        seed.run(null);

        JsonNode list = get("viewer", "/projects").json();
        assertThat(list.valueStream().map(project -> project.get("maintask").stringValue()))
                .containsExactlyInAnyOrder("order-service", "LogProject", "giris");
        long giris = list.valueStream().filter(project -> project.get("maintask").stringValue().equals("giris"))
                .findFirst().orElseThrow().get("id").longValue();
        JsonNode girisRoutes = get("viewer", "/projects/" + giris + "/routes").json();
        assertThat(girisRoutes.valueStream().map(route -> route.get("subtask").stringValue()))
                .containsExactly("login", "logout");
        assertThat(girisRoutes.get(0).get("anonymous").booleanValue()).isTrue();
        assertThat(get("viewer", "/response-profiles").json().size()).isEqualTo(1);
        long route = girisRoutes.get(0).get("id").longValue();
        assertThat(get("viewer", "/routes/" + route + "/history").json().get(0).get("changedBy").stringValue())
                .isEqualTo("seed");
    }
}

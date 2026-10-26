package com.acme.yapi.admin.project;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.admin.security.CurrentUser;
import com.acme.yapi.admin.security.Role;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {

    private final ProjectService projects;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projects, CurrentUser currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @GetMapping("/catalog/projects")
    public List<ProjectService.CatalogView> catalog(@RequestParam(required = false) String query) {
        currentUser.get();
        return projects.searchCatalog(query);
    }

    @GetMapping("/projects")
    public List<ProjectService.View> list(@RequestParam(required = false) String query,
                                          @RequestParam(required = false) String catalogStatus) {
        currentUser.get();
        return projects.list(query, catalogStatus);
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectService.View register(@RequestBody ProjectService.Register body) {
        return projects.register(currentUser.get().require(Role.ROUTE_EDITOR), body);
    }

    @GetMapping("/projects/{id}")
    public ProjectService.View get(@PathVariable long id) {
        currentUser.get();
        return projects.get(id);
    }

    @PatchMapping("/projects/{id}")
    public ProjectService.View patch(@PathVariable long id, @RequestBody JsonNode body) {
        return projects.patch(currentUser.get().require(Role.ROUTE_EDITOR), id, body);
    }

    @PutMapping("/projects/{id}/environments/{environment}")
    public ProjectService.EnvironmentResult environment(@PathVariable long id, @PathVariable String environment,
                                                        @RequestBody ProjectService.EnvironmentSettings body) {
        return projects.putEnvironment(currentUser.get(), id, environment, body);
    }

    @GetMapping("/projects/{id}/permissions")
    public List<ProjectService.PermissionView> permissions(@PathVariable long id,
                                                           @RequestParam(name = "env", required = false) String environment) {
        currentUser.get();
        return projects.permissions(id, environment);
    }
}

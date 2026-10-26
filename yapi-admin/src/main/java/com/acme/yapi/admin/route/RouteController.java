package com.acme.yapi.admin.route;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acme.yapi.admin.db.Versions;
import com.acme.yapi.admin.security.CurrentUser;
import com.acme.yapi.admin.security.Role;
import com.acme.yapi.admin.web.ApiException;
import com.acme.yapi.core.validation.FieldError;

@RestController
@RequestMapping("/api/v1")
public class RouteController {

    private final RouteService routes;
    private final CurrentUser currentUser;

    public RouteController(RouteService routes, CurrentUser currentUser) {
        this.routes = routes;
        this.currentUser = currentUser;
    }

    @GetMapping("/projects/{projectId}/routes")
    public List<RouteService.Summary> list(@PathVariable long projectId, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String query) {
        currentUser.get();
        return routes.list(projectId, status, query);
    }

    /** Across projects; only {@code alarmService=true} is supported, for the alarm route pickers. */
    @GetMapping("/routes")
    public List<RouteService.Summary> listAcrossProjects(@RequestParam(required = false) Boolean alarmService) {
        currentUser.get();
        if (!Boolean.TRUE.equals(alarmService)) {
            throw ApiException.validation(
                    List.of(new FieldError("alarmService", "Şimdilik yalnızca alarmService=true destekleniyor")));
        }
        return routes.alarmServices();
    }

    @PostMapping("/projects/{projectId}/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteService.View create(@PathVariable long projectId, @RequestBody RouteService.Body body) {
        return routes.create(currentUser.get().require(Role.ROUTE_EDITOR).username(), projectId, body);
    }

    @GetMapping("/routes/{id}")
    public RouteService.View get(@PathVariable long id) {
        currentUser.get();
        return routes.get(id);
    }

    @PutMapping("/routes/{id}")
    public RouteService.View update(@PathVariable long id, @RequestBody RouteService.Body body) {
        return routes.update(currentUser.get().require(Role.ROUTE_EDITOR).username(), id, body);
    }

    @PostMapping("/routes/{id}/archive")
    public RouteService.View archive(@PathVariable long id,
                                     @RequestBody(required = false) RouteService.StatusChange body) {
        return routes.archive(currentUser.get().require(Role.ROUTE_EDITOR).username(), id, body);
    }

    @PostMapping("/routes/{id}/restore")
    public RouteService.View restore(@PathVariable long id,
                                     @RequestBody(required = false) RouteService.StatusChange body) {
        return routes.restore(currentUser.get().require(Role.ROUTE_EDITOR).username(), id, body);
    }

    @PostMapping("/routes/{id}/rollback")
    public RouteService.View rollback(@PathVariable long id, @RequestBody RouteService.Rollback body) {
        return routes.rollback(currentUser.get().require(Role.ROUTE_EDITOR).username(), id, body);
    }

    @GetMapping("/routes/{id}/history")
    public List<Versions.Entry> history(@PathVariable long id) {
        currentUser.get();
        return routes.history(id);
    }

    @GetMapping("/routes/{id}/history/{version}")
    public RouteService.VersionView version(@PathVariable long id, @PathVariable int version) {
        currentUser.get();
        return routes.version(id, version);
    }

    @GetMapping("/routes/{id}/compare")
    public RouteService.Comparison compare(@PathVariable long id, @RequestParam int from, @RequestParam int to) {
        currentUser.get();
        return routes.compare(id, from, to);
    }

    @PostMapping("/routes/validate")
    public RouteService.Validation validate(@RequestBody RouteService.Body body) {
        currentUser.get();
        return routes.validate(body);
    }

    @PostMapping("/routes/preview")
    public RouteService.Preview preview(@RequestBody RouteService.Trial trial) {
        return routes.preview(currentUser.get().username(), trial);
    }

    @GetMapping("/permissions/{code}/routes")
    public List<RouteService.PermissionRoute> usingPermission(@PathVariable String code) {
        currentUser.get();
        return routes.usingPermission(code);
    }
}

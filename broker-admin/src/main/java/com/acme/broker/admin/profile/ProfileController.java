package com.acme.broker.admin.profile;

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

import com.acme.broker.admin.db.Versions;
import com.acme.broker.admin.security.CurrentUser;
import com.acme.broker.admin.security.Role;

@RestController
@RequestMapping("/api/v1/response-profiles")
public class ProfileController {

    private final ProfileService profiles;
    private final CurrentUser currentUser;

    public ProfileController(ProfileService profiles, CurrentUser currentUser) {
        this.profiles = profiles;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ProfileService.Summary> list() {
        currentUser.get();
        return profiles.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileService.Detail create(@RequestBody ProfileService.Body body) {
        return profiles.create(currentUser.get().require(Role.ROUTE_EDITOR).username(), body);
    }

    @GetMapping("/{id}")
    public ProfileService.Detail get(@PathVariable long id) {
        currentUser.get();
        return profiles.get(id);
    }

    @PutMapping("/{id}")
    public ProfileService.Detail update(@PathVariable long id, @RequestBody ProfileService.Body body) {
        return profiles.update(currentUser.get().require(Role.ROUTE_EDITOR).username(), id, body);
    }

    @PostMapping("/{id}/rollback")
    public ProfileService.Detail rollback(@PathVariable long id, @RequestBody ProfileService.Rollback body) {
        return profiles.rollback(currentUser.get().require(Role.ROUTE_EDITOR).username(), id, body);
    }

    @GetMapping("/{id}/history")
    public List<Versions.Entry> history(@PathVariable long id) {
        currentUser.get();
        return profiles.history(id);
    }

    @GetMapping("/{id}/history/{version}")
    public ProfileService.VersionView version(@PathVariable long id, @PathVariable int version) {
        currentUser.get();
        return profiles.version(id, version);
    }

    @GetMapping("/{id}/compare")
    public ProfileService.Comparison compare(@PathVariable long id, @RequestParam int from, @RequestParam int to) {
        currentUser.get();
        return profiles.compare(id, from, to);
    }

    @PostMapping("/preview")
    public ProfileService.PreviewResult preview(@RequestBody ProfileService.PreviewRequest request) {
        currentUser.get();
        return profiles.preview(request);
    }
}

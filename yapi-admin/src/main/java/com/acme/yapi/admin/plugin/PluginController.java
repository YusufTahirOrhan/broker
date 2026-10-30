package com.acme.yapi.admin.plugin;

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

@RestController
@RequestMapping("/api/v1/plugins")
public class PluginController {

    private final PluginService plugins;
    private final CurrentUser currentUser;

    public PluginController(PluginService plugins, CurrentUser currentUser) {
        this.plugins = plugins;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<PluginService.Summary> list() {
        currentUser.get();
        return plugins.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PluginService.Detail create(@RequestBody PluginService.Body body) {
        return plugins.create(currentUser.get(), body);
    }

    @GetMapping("/{id}")
    public PluginService.Detail get(@PathVariable long id) {
        currentUser.get();
        return plugins.get(id);
    }

    @PutMapping("/{id}")
    public PluginService.Detail update(@PathVariable long id, @RequestBody PluginService.Body body) {
        return plugins.update(currentUser.get(), id, body);
    }

    @PostMapping("/{id}/rollback")
    public PluginService.Detail rollback(@PathVariable long id, @RequestBody PluginService.Rollback body) {
        return plugins.rollback(currentUser.get(), id, body);
    }

    @GetMapping("/{id}/history")
    public List<Versions.Entry> history(@PathVariable long id) {
        currentUser.get();
        return plugins.history(id);
    }

    @GetMapping("/{id}/history/{version}")
    public PluginService.VersionView version(@PathVariable long id, @PathVariable int version) {
        currentUser.get();
        return plugins.version(id, version);
    }

    @GetMapping("/{id}/compare")
    public PluginService.Comparison compare(@PathVariable long id, @RequestParam int from, @RequestParam int to) {
        currentUser.get();
        return plugins.compare(id, from, to);
    }

    @PostMapping("/preview")
    public PluginService.PreviewResult preview(@RequestBody PluginService.PreviewRequest request) {
        return plugins.preview(currentUser.get(), request);
    }
}

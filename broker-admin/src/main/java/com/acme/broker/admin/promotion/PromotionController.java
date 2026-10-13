package com.acme.broker.admin.promotion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.acme.broker.admin.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {

    private final PromotionService promotions;
    private final CurrentUser currentUser;

    public PromotionController(PromotionService promotions, CurrentUser currentUser) {
        this.promotions = promotions;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PromotionService.View create(@RequestBody PromotionService.Create body) {
        return promotions.create(currentUser.get(), body);
    }

    @GetMapping
    public PromotionService.Page list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String targetEnv,
                                      @RequestParam(required = false) String subjectType,
                                      @RequestParam(required = false) Long projectId,
                                      @RequestParam(defaultValue = "false") boolean mine,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return promotions.list(currentUser.get(), status, targetEnv, subjectType, projectId, mine, page, size);
    }

    @GetMapping("/{id}")
    public PromotionService.View get(@PathVariable long id) {
        return promotions.get(currentUser.get(), id);
    }

    @PostMapping("/{id}/approve")
    public PromotionService.View approve(@PathVariable long id,
                                         @RequestBody(required = false) PromotionService.Decision body) {
        return promotions.approve(currentUser.get(), id, body);
    }

    @PostMapping("/{id}/reject")
    public PromotionService.View reject(@PathVariable long id, @RequestBody PromotionService.Decision body) {
        return promotions.reject(currentUser.get(), id, body);
    }

    @PostMapping("/{id}/cancel")
    public PromotionService.View cancel(@PathVariable long id) {
        return promotions.cancel(currentUser.get(), id);
    }

    @PostMapping("/{id}/retry")
    public PromotionService.View retry(@PathVariable long id) {
        return promotions.retry(currentUser.get(), id);
    }
}

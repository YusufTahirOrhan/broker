package com.acme.yapi.runtime.web;

import java.io.IOException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** The single endpoint frontends call: a flat JSON body with {@code maintask} and {@code subtask}. */
@RestController
public class YapiController {

    private final YapiHandler handler;

    public YapiController(YapiHandler handler) {
        this.handler = handler;
    }

    @PostMapping("/yapi")
    public void yapi(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handler.handle(request, response);
    }
}

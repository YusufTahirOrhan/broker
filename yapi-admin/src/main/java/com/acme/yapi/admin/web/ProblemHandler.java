package com.acme.yapi.admin.web;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.acme.yapi.admin.security.LoginUrl;

import tools.jackson.databind.node.ObjectNode;

@RestControllerAdvice
public class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    private final LoginUrl loginUrl;

    public ProblemHandler(LoginUrl loginUrl) {
        this.loginUrl = loginUrl;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ObjectNode> api(ApiException e) {
        if (e.status() != HttpStatus.UNAUTHORIZED) {
            return Problems.of(e);
        }
        Map<String, Object> properties = new java.util.LinkedHashMap<>(e.properties());
        properties.putAll(loginUrl.properties());
        return Problems.of(e.status(), e.code(), e.getMessage(), e.errors(), properties);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ObjectNode> unreadable(HttpMessageNotReadableException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Gövde okunamadı: geçerli bir JSON değil.");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ObjectNode> badParameter(Exception e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "İstek parametresi eksik ya da hatalı.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ObjectNode> mediaType(HttpMediaTypeNotSupportedException e) {
        return Problems.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VALIDATION_FAILED", "Gövde JSON olmalı.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ObjectNode> notFound(NoResourceFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "Böyle bir uç nokta yok.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ObjectNode> method(HttpRequestMethodNotSupportedException e) {
        return Problems.of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Bu uç nokta bu method'u desteklemiyor.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ObjectNode> denied(AccessDeniedException e) {
        return Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bu işlem için rolünüz yetmiyor.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> unexpected(Exception e) {
        log.error("Beklenmeyen hata", e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Beklenmeyen bir hata oluştu.",
                List.of(), Map.of());
    }
}

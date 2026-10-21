package com.zlf.datapilot.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把业务/参数/数据完整性异常统一转成 400，且透出自定义原因。
 * - IllegalArgumentException：白名单校验 / 指标名冲突 / gate 参数校验等，直接 400
 * - DataIntegrityViolationException：撞唯一键（并发兜底）或 NOT NULL 兜底，转 400 而非 500
 * - MethodArgumentNotValidException：@Valid 非空校验失败，汇总字段错误
 * - ResponseStatusException：写开关关闭等 403，把 reason 透出（不再只有通用 Forbidden）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "数据完整性冲突：" + message(e)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        String reason = e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", reason == null || reason.isBlank() ? e.getClass().getSimpleName() : reason));
    }

    private String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}

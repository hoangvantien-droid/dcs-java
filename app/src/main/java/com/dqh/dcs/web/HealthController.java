package com.dqh.dcs.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * web/HealthController — kiểm tra sống (port routes/home.ts GET /healthz).
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok", "phase", "receipt");
    }
}

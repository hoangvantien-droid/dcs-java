package com.dqh.dcs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Điểm khởi động DCS (port src/server.ts).
 */
@SpringBootApplication
public class DcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DcsApplication.class, args);
    }
}

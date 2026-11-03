package com.dqh.dcs.web;

/**
 * web/RedirectToLoginException — tín hiệu "chưa đăng nhập" → chuyển hướng
 * /login (port requireUser của src/middleware/auth.ts). GlobalExceptionHandler
 * biến thành 302 /login.
 */
public class RedirectToLoginException extends RuntimeException {

    public RedirectToLoginException() {
        super("Chưa đăng nhập");
    }
}

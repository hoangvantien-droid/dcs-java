package com.dqh.dcs.common;

import java.util.Map;

/**
 * common/BusinessException — lỗi vi phạm quy tắc nghiệp vụ, mang mã để tầng web
 * ánh xạ ra thông báo cho người dùng (port src/lib/errors.ts BusinessError).
 *
 * Ba loại lỗi (DCS-8 §3.3):
 *  1. BusinessException  — vi phạm quy tắc nghiệp vụ; web/ chuyển thành thông báo.
 *  2. Lỗi ràng buộc DB   — KHÔNG bắt rồi nuốt (xem DbErrors.isDbConstraintError).
 *  3. Lỗi không lường    — ghi log, trả lỗi chung, không lộ chi tiết.
 *
 * httpStatus mặc định 422 (dữ liệu hợp lệ cú pháp nhưng sai nghiệp vụ).
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final transient Map<String, Object> details;

    public BusinessException(String code, String message) {
        this(code, message, 422, null);
    }

    public BusinessException(String code, String message, int httpStatus) {
        this(code, message, httpStatus, null);
    }

    public BusinessException(String code, String message, int httpStatus, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}

package com.dqh.dcs.common;

import java.sql.SQLException;

/**
 * common/DbErrors — nhận diện lỗi ràng buộc PostgreSQL (port src/lib/errors.ts
 * isDbConstraintError/dbConstraintName).
 *
 * Mã lớp 23 = integrity_constraint_violation (23505 unique, 23503 foreign key,
 * 23514 check, 23502 not null). Nếu lỗi này chạm tới tầng ứng dụng, nghĩa là
 * tầng service ĐÃ THIẾU một kiểm tra — phải sửa chỗ đó, KHÔNG bọc try-catch cho
 * êm (DCS-9 bẫy số 1). Lớp này CHỈ nhận diện để ghi log, không nuốt lỗi.
 *
 * Dùng SQLState chuẩn JDBC + lấy tên ràng buộc qua phản chiếu
 * (org.postgresql.util.PSQLException#getServerErrorMessage) để KHÔNG phụ thuộc
 * driver ở compile-time (driver chỉ có ở scope runtime).
 */
public final class DbErrors {

    private DbErrors() {
    }

    /** Trích SQLState của lỗi (đi sâu qua chuỗi nguyên nhân tìm SQLException). */
    public static String sqlState(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** Lỗi có phải vi phạm ràng buộc toàn vẹn (SQLState lớp 23) không. */
    public static boolean isDbConstraintError(Throwable e) {
        String state = sqlState(e);
        return state != null && state.startsWith("23");
    }

    /**
     * Tên ràng buộc bị vi phạm, nếu có — hữu ích cho log. Lấy qua phản chiếu để
     * tránh phụ thuộc lớp driver lúc biên dịch.
     */
    public static String dbConstraintName(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLException) {
                String name = constraintViaReflection(cur);
                if (name != null) {
                    return name;
                }
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static String constraintViaReflection(Throwable e) {
        try {
            Object sem = e.getClass().getMethod("getServerErrorMessage").invoke(e);
            if (sem == null) {
                return null;
            }
            Object constraint = sem.getClass().getMethod("getConstraint").invoke(sem);
            return constraint == null ? null : constraint.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}

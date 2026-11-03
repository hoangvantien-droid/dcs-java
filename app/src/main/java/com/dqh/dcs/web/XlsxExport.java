package com.dqh.dcs.web;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import jakarta.servlet.http.HttpServletResponse;

/**
 * web/XlsxExport — bộ dựng workbook .xlsx dùng chung (port src/lib/xlsx.ts,
 * QĐ-I: exceljs→POI). Mô tả cột theo khai báo (header + hàm trích giá trị) rồi
 * stream ra response.
 */
public final class XlsxExport {

    private XlsxExport() {
    }

    /** Một cột: tiêu đề + cách lấy giá trị ô từ một dòng. */
    public record Column<R>(String header, Function<R, String> value) {
    }

    /** Dựng workbook một sheet từ định nghĩa cột + danh sách dòng. */
    public static <R> Workbook buildWorkbook(List<Column<R>> columns, List<R> rows, String sheetName) {
        Workbook wb = new XSSFWorkbook();
        Sheet ws = wb.createSheet(sheetName == null ? "Export" : sheetName);
        Row header = ws.createRow(0);
        Font bold = wb.createFont();
        bold.setBold(true);
        var boldStyle = wb.createCellStyle();
        boldStyle.setFont(bold);
        for (int c = 0; c < columns.size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(columns.get(c).header());
            cell.setCellStyle(boldStyle);
        }
        int r = 1;
        for (R row : rows) {
            Row sheetRow = ws.createRow(r++);
            for (int c = 0; c < columns.size(); c++) {
                String v = columns.get(c).value().apply(row);
                sheetRow.createCell(c).setCellValue(v == null ? "" : v);
            }
        }
        return wb;
    }

    /** Tên tệp theo quy ước: {prefix}-YYYYMMDD-HHmmss.xlsx (giờ máy chủ). */
    public static String exportFileName(String prefix, LocalDateTime at) {
        return prefix + "-" + at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".xlsx";
    }

    /** Đặt header .xlsx chuẩn và stream workbook ra response. */
    public static void stream(HttpServletResponse resp, Workbook workbook, String fileName)
            throws IOException {
        resp.setHeader("Content-Type",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        try (workbook) {
            workbook.write(resp.getOutputStream());
        }
        resp.getOutputStream().flush();
    }
}

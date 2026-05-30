package com.spendlens.service;

import com.spendlens.model.AnalysisResponse;
import com.spendlens.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Service
public class ExcelExportService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Generate Excel report from analysis response
     * Returns byte array that can be downloaded as .xlsx file
     */
    public byte[] generateExcelReport(AnalysisResponse analysis) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {

            // Create three sheets
            createSummarySheet(workbook, analysis);
            createTransactionSheet(workbook, analysis);
            createWeeklyBreakdownSheet(workbook, analysis);

            // Write to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            log.info("Generated Excel report successfully");
            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("Error generating Excel report: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * SHEET 1: Dashboard Summary
     * Shows account info, totals, and category breakdown
     */
    private void createSummarySheet(Workbook workbook, AnalysisResponse analysis) {
        Sheet sheet = workbook.createSheet("Dashboard Summary");
        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("SpendLens - Bank Statement Analysis");
        titleCell.setCellStyle(createTitleStyle(workbook));
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));

        rowNum++; // blank row

        // Statement Period
        Row periodRow = sheet.createRow(rowNum++);
        periodRow.createCell(0).setCellValue("Statement Period");
        periodRow.createCell(1).setCellValue(
                analysis.getStatementStartDate() + " to " + analysis.getStatementEndDate()
        );

        // Account Info
        Row accountRow = sheet.createRow(rowNum++);
        accountRow.createCell(0).setCellValue("Account Holder");
        accountRow.createCell(1).setCellValue(analysis.getAccountHolder() != null ? analysis.getAccountHolder() : "N/A");

        Row accountNumRow = sheet.createRow(rowNum++);
        accountNumRow.createCell(0).setCellValue("Account Number");
        accountNumRow.createCell(1).setCellValue(analysis.getAccountNumber() != null ? analysis.getAccountNumber() : "N/A");

        rowNum++; // blank row

        // Summary Metrics Header
        Row metricsHeaderRow = sheet.createRow(rowNum++);
        metricsHeaderRow.createCell(0).setCellValue("Summary Metrics");
        metricsHeaderRow.getCell(0).setCellStyle(createBoldStyle(workbook));

        // Opening Balance
        Row openingRow = sheet.createRow(rowNum++);
        openingRow.createCell(0).setCellValue("Opening Balance");
        Cell openingValCell = openingRow.createCell(1);
        openingValCell.setCellValue(
                analysis.getOpeningBalance() != null ? analysis.getOpeningBalance().doubleValue() : 0
        );
        openingValCell.setCellStyle(createCurrencyStyle(workbook));

        // Closing Balance
        Row closingRow = sheet.createRow(rowNum++);
        closingRow.createCell(0).setCellValue("Closing Balance");
        Cell closingValCell = closingRow.createCell(1);
        closingValCell.setCellValue(
                analysis.getClosingBalance() != null ? analysis.getClosingBalance().doubleValue() : 0
        );
        closingValCell.setCellStyle(createCurrencyStyle(workbook));

        // Total Income
        Row incomeRow = sheet.createRow(rowNum++);
        incomeRow.createCell(0).setCellValue("Total Income");
        Cell incomeValCell = incomeRow.createCell(1);
        incomeValCell.setCellValue(
                analysis.getTotalIncome() != null ? analysis.getTotalIncome().doubleValue() : 0
        );
        incomeValCell.setCellStyle(createCurrencyStyle(workbook));

        // Total Expenses
        Row expenseRow = sheet.createRow(rowNum++);
        expenseRow.createCell(0).setCellValue("Total Expenses");
        Cell expenseValCell = expenseRow.createCell(1);
        expenseValCell.setCellValue(
                analysis.getTotalExpenses() != null ? analysis.getTotalExpenses().doubleValue() : 0
        );
        expenseValCell.setCellStyle(createCurrencyStyle(workbook));

        // Total Transactions
        Row countRow = sheet.createRow(rowNum++);
        countRow.createCell(0).setCellValue("Total Transactions");
        countRow.createCell(1).setCellValue(analysis.getTransactionCount() != null ? analysis.getTransactionCount() : 0);

        rowNum++; // blank row

        // Category Breakdown Header
        Row categoryHeaderRow = sheet.createRow(rowNum++);
        categoryHeaderRow.createCell(0).setCellValue("Spending by Category");
        categoryHeaderRow.getCell(0).setCellStyle(createBoldStyle(workbook));

        // Table headers
        Row tableHeaderRow = sheet.createRow(rowNum++);
        tableHeaderRow.createCell(0).setCellValue("Category");
        tableHeaderRow.createCell(1).setCellValue("Amount (₹)");
        tableHeaderRow.getCell(0).setCellStyle(createHeaderStyle(workbook));
        tableHeaderRow.getCell(1).setCellStyle(createHeaderStyle(workbook));

        // Category data (sorted by amount)
        if (analysis.getCategoryBreakdown() != null) {
            for (Map.Entry<String, BigDecimal> entry : analysis.getCategoryBreakdown().entrySet()) {
                Row categoryRow = sheet.createRow(rowNum++);
                categoryRow.createCell(0).setCellValue(entry.getKey());
                Cell categoryValCell = categoryRow.createCell(1);
                categoryValCell.setCellValue(entry.getValue().doubleValue());
                categoryValCell.setCellStyle(createCurrencyStyle(workbook));
            }
        }

        // Auto-size columns
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    /**
     * SHEET 2: Transaction Data
     * All transactions with dates, descriptions, categories, amounts
     */
    private void createTransactionSheet(Workbook workbook, AnalysisResponse analysis) {
        Sheet sheet = workbook.createSheet("Transaction Data");

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Date", "Description", "Category", "Debit (₹)", "Credit (₹)", "Balance (₹)"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(createHeaderStyle(workbook));
        }

        // Add transaction data
        int rowNum = 1;
        if (analysis.getTransactions() != null) {
            for (Transaction txn : analysis.getTransactions()) {
                Row row = sheet.createRow(rowNum++);

                // Date
                Cell dateCell = row.createCell(0);
                dateCell.setCellValue(txn.getTxnDate() != null ? txn.getTxnDate().format(DATE_FORMATTER) : "");
                dateCell.setCellStyle(createDateStyle(workbook));

                // Description
                row.createCell(1).setCellValue(txn.getDescription() != null ? txn.getDescription() : "");

                // Category
                row.createCell(2).setCellValue(txn.getCategory() != null ? txn.getCategory() : "");

                // Debit (expense)
                Cell debitCell = row.createCell(3);
                if (txn.getDebit() != null && txn.getDebit().compareTo(BigDecimal.ZERO) > 0) {
                    debitCell.setCellValue(txn.getDebit().doubleValue());
                }
                debitCell.setCellStyle(createCurrencyStyle(workbook));

                // Credit (income)
                Cell creditCell = row.createCell(4);
                if (txn.getCredit() != null && txn.getCredit().compareTo(BigDecimal.ZERO) > 0) {
                    creditCell.setCellValue(txn.getCredit().doubleValue());
                }
                creditCell.setCellStyle(createCurrencyStyle(workbook));

                // Balance
                Cell balanceCell = row.createCell(5);
                if (txn.getBalance() != null) {
                    balanceCell.setCellValue(txn.getBalance().doubleValue());
                }
                balanceCell.setCellStyle(createCurrencyStyle(workbook));
            }
        }

        // Auto-size columns
        for (int i = 0; i < 6; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * SHEET 3: Weekly Breakdown
     * Week-by-week spending with weekday/weekend splits
     */
    private void createWeeklyBreakdownSheet(Workbook workbook, AnalysisResponse analysis) {
        Sheet sheet = workbook.createSheet("Weekly Breakdown");

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Week", "Period", "Weekday Spend (₹)", "Weekend Spend (₹)", "Total (₹)", "Transactions"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(createHeaderStyle(workbook));
        }

        // Add weekly data
        int rowNum = 1;
        if (analysis.getWeeklyData() != null) {
            for (AnalysisResponse.WeeklyData weekly : analysis.getWeeklyData()) {
                Row row = sheet.createRow(rowNum++);

                // Week number
                row.createCell(0).setCellValue("Week " + weekly.getWeekNumber());

                // Period (Monday to Sunday)
                row.createCell(1).setCellValue(
                        weekly.getWeekStartDate().format(DATE_FORMATTER) + " to " +
                                weekly.getWeekEndDate().format(DATE_FORMATTER)
                );

                // Weekday spend (Tue-Fri)
                Cell weekdayCell = row.createCell(2);
                weekdayCell.setCellValue(weekly.getWeekdaySpend().doubleValue());
                weekdayCell.setCellStyle(createCurrencyStyle(workbook));

                // Weekend spend (Sat, Sun, Mon)
                Cell weekendCell = row.createCell(3);
                weekendCell.setCellValue(weekly.getWeekendSpend().doubleValue());
                weekendCell.setCellStyle(createCurrencyStyle(workbook));

                // Total spend
                Cell totalCell = row.createCell(4);
                totalCell.setCellValue(weekly.getTotalWeekSpend().doubleValue());
                totalCell.setCellStyle(createCurrencyStyle(workbook));

                // Transaction count
                row.createCell(5).setCellValue(weekly.getTransactionCount());
            }
        }

        // Auto-size columns
        for (int i = 0; i < 6; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    // ============ Style Helper Methods ============

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFont(createBoldFont(workbook));
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }

    private CellStyle createBoldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFont(createBoldFont(workbook));
        return style;
    }

    private CellStyle createCurrencyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("₹ #,##0.00"));
        return style;
    }

    private CellStyle createDateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("dd-mm-yyyy"));
        return style;
    }

    private Font createBoldFont(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        return font;
    }
}
package com.spendlens.service;

import com.spendlens.model.AnalysisResponse;
import com.spendlens.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatementService {

    private final PdfParserService pdfParserService;
    private final CategorizationService categorizationService;
    private final AggregationService aggregationService;
    private final ExcelExportService excelExportService;

    /**
     * Main workflow: Analyze a bank statement PDF
     *
     * Flow:
     * 1. Validate file
     * 2. Parse PDF → extract transactions
     * 3. Categorize each transaction
     * 4. Aggregate data (totals, categories, weekly splits)
     * 5. Return AnalysisResponse
     */
    public AnalysisResponse analyzeStatement(MultipartFile statementFile) throws IOException {
        log.info("Starting statement analysis for file: {}", statementFile.getOriginalFilename());

        // Step 0: Validate file
        validateFile(statementFile);

        try {
            // Step 1: Parse PDF and extract transactions
            log.debug("Step 1: Extracting transactions from PDF...");
            List<Transaction> transactions = pdfParserService.extractTransactions(
                    statementFile.getInputStream()
            );

            if (transactions.isEmpty()) {
                throw new IOException("No transactions found in the PDF statement");
            }
            log.info("✅ Successfully extracted {} transactions", transactions.size());

            // Step 2: Categorize each transaction
            log.debug("Step 2: Categorizing transactions...");
            categorizationService.categorizeAllTransactions(transactions);
            log.info("✅ Successfully categorized {} transactions", transactions.size());

            // Step 3: Aggregate data and create response
            log.debug("Step 3: Aggregating data...");
            AnalysisResponse response = new AnalysisResponse();
            response.setTransactions(transactions);  // Include raw transactions

            // Extract statement metadata from transactions
            if (!transactions.isEmpty()) {
                extractStatementMetadata(transactions, response);
            }

            // Perform aggregations (totals, categories, weekly splits)
            aggregationService.aggregateData(transactions, response);

            log.info("✅ Analysis complete!");
            log.info("   • Total Expenses: ₹{}", response.getTotalExpenses());
            log.info("   • Total Income: ₹{}", response.getTotalIncome());
            log.info("   • Categories: {}", response.getCategoryBreakdown().size());
            log.info("   • Weeks: {}", response.getWeeklyData().size());

            return response;

        } catch (IOException e) {
            log.error("Error analyzing statement: {}", e.getMessage(), e);
            throw new IOException("Failed to analyze the statement file", e);
        }
    }

    /**
     * Extract account metadata from transactions
     * (Statement period, opening/closing balances, etc.)
     */
    private void extractStatementMetadata(List<Transaction> transactions, AnalysisResponse response) {
        try {
            // Find earliest and latest transaction dates
            Transaction firstTxn = transactions.stream()
                    .filter(t -> t.getTxnDate() != null)
                    .min((t1, t2) -> t1.getTxnDate().compareTo(t2.getTxnDate()))
                    .orElse(null);

            Transaction lastTxn = transactions.stream()
                    .filter(t -> t.getTxnDate() != null)
                    .max((t1, t2) -> t1.getTxnDate().compareTo(t2.getTxnDate()))
                    .orElse(null);

            if (firstTxn != null) {
                response.setStatementStartDate(firstTxn.getTxnDate());
            }
            if (lastTxn != null) {
                response.setStatementEndDate(lastTxn.getTxnDate());
            }

            // Opening balance = First transaction balance + first transaction debit/credit
            Transaction openingTxn = transactions.get(0);
            if (openingTxn.getBalance() != null) {
                if (openingTxn.getDebit() != null && openingTxn.getDebit().signum() > 0) {
                    response.setOpeningBalance(openingTxn.getBalance().add(openingTxn.getDebit()));
                } else if (openingTxn.getCredit() != null && openingTxn.getCredit().signum() > 0) {
                    response.setOpeningBalance(openingTxn.getBalance().subtract(openingTxn.getCredit()));
                }
            }

            // Closing balance = Last transaction balance
            Transaction closingTxn = transactions.get(transactions.size() - 1);
            if (closingTxn.getBalance() != null) {
                response.setClosingBalance(closingTxn.getBalance());
            }

            log.debug("Statement period: {} to {}",
                    response.getStatementStartDate(), response.getStatementEndDate());

        } catch (Exception e) {
            log.warn("Could not extract statement metadata: {}", e.getMessage());
            // Continue even if metadata extraction fails
        }
    }

    /**
     * Generate Excel export from analysis response
     * Called by controller when user wants to download Excel
     */
    public byte[] getExcelExport(AnalysisResponse analysis) throws IOException {
        log.debug("Generating Excel export...");
        return excelExportService.generateExcelReport(analysis);
    }

    /**
     * Validate the uploaded PDF file
     */
    private void validateFile(MultipartFile file) throws IOException {
        // Check if file exists and not empty
        if (file == null || file.isEmpty()) {
            throw new IOException("File is empty or not provided");
        }

        // Check file extension
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new IOException("Only PDF files are supported. Please upload a .pdf file");
        }

        // Check file size (max 50 MB)
        long maxFileSize = 50 * 1024 * 1024; // 50 MB
        if (file.getSize() > maxFileSize) {
            throw new IOException("File size exceeds maximum limit of 50 MB");
        }

        log.debug("File validation passed: {} ({} bytes)", originalFilename, file.getSize());
    }
}
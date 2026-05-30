package com.spendlens.service;

import com.spendlens.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PdfParserService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter VALUE_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");

    // Detects the START of a transaction line: "30-04-2026 21:00:44 01 May 2026 210043486118 ..."
    private static final Pattern TXN_START = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})\\s+(\\d+)\\s+(.*)"
    );

    // Extracts amounts from END of combined block: "... 33 196.75 84,649.34"
    // Groups: (branchCode) (amount) (balance)
    private static final Pattern AMOUNTS_AT_END = Pattern.compile(
            "\\s+(\\d{1,4})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$"
    );

    /**
     * Extract transactions from Canara Bank PDF
     */
    public List<Transaction> extractTransactions(InputStream pdfInputStream) throws IOException {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = PDDocument.load(pdfInputStream)) {
            log.info("PDF loaded. Total pages: {}", document.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            String pdfText = stripper.getText(document);
            log.debug("PDF text extracted. Length: {} characters", pdfText.length());

            transactions = parseTransactions(pdfText);

        } catch (IOException e) {
            log.error("Error parsing PDF: {}", e.getMessage(), e);
            throw new IOException("Failed to parse PDF statement", e);
        }

        log.info("Extracted {} transactions from PDF", transactions.size());
        return transactions;
    }

    /**
     * Group lines into transaction blocks then parse each block
     */
    private List<Transaction> parseTransactions(String pdfText) {
        List<Transaction> transactions = new ArrayList<>();
        String[] lines = pdfText.split("\n");

        List<String> currentBlock = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (TXN_START.matcher(line).find()) {
                // New transaction found — process previous block first
                if (!currentBlock.isEmpty()) {
                    Transaction txn = parseTransactionBlock(currentBlock);
                    if (txn != null) {
                        transactions.add(txn);
                    }
                    currentBlock.clear();
                }
                currentBlock.add(line);

            } else if (!currentBlock.isEmpty()) {
                // Continuation line — append to current block
                currentBlock.add(line);
            }
        }

        // Process last block
        if (!currentBlock.isEmpty()) {
            Transaction txn = parseTransactionBlock(currentBlock);
            if (txn != null) transactions.add(txn);
        }

        return transactions;
    }

    /**
     * Parse a single transaction block (may span multiple lines)
     */
    private Transaction parseTransactionBlock(List<String> block) {
        if (block.isEmpty()) return null;

        // Combine all lines into one string
        String combined = String.join(" ", block)
                .replaceAll("\\s+", " ")
                .trim();

        try {
            // Extract: txnDate, valueDate, chequeNo from the START
            Matcher startMatcher = TXN_START.matcher(combined);
            if (!startMatcher.find()) return null;

            String txnDateStr = startMatcher.group(1);   // "30-04-2026"
            String valueDateStr = startMatcher.group(2); // "01 May 2026"
            String chequeNo = startMatcher.group(3);     // "210043486118"

            // Extract: branchCode, amount, balance from the END
            Matcher amountsMatcher = AMOUNTS_AT_END.matcher(combined);
            if (!amountsMatcher.find()) {
                log.debug("Could not extract amounts from: {}",
                        combined.substring(0, Math.min(80, combined.length())));
                return null;
            }

            String amount1Str = amountsMatcher.group(2); // debit or credit amount
            String balanceStr = amountsMatcher.group(3); // always balance

            // Description = everything between chequeNo and the amounts at end
            String afterCheque = combined.substring(
                    combined.indexOf(chequeNo) + chequeNo.length()
            ).trim();

            String description = afterCheque
                    .replaceAll("\\s+\\d{1,4}\\s+[\\d,]+\\.\\d{2}\\s+[\\d,]+\\.\\d{2}\\s*$", "")
                    .trim();

            // Determine debit or credit from description
            boolean isCredit = description.toUpperCase().contains("UPI/CR/") ||
                    description.toUpperCase().contains("/CR/");

            BigDecimal amount = parseAmount(amount1Str);
            BigDecimal balance = parseAmount(balanceStr);

            BigDecimal debit = isCredit ? BigDecimal.ZERO : amount;
            BigDecimal credit = isCredit ? amount : BigDecimal.ZERO;

            // Skip if amount is zero
            if (amount.compareTo(BigDecimal.ZERO) == 0) return null;

            return Transaction.builder()
                    .txnDate(parseDate(txnDateStr, DATE_FORMATTER))
                    .valueDate(parseDate(valueDateStr.trim(), VALUE_DATE_FORMATTER))
                    .description(cleanDescription(description))
                    .debit(debit)
                    .credit(credit)
                    .balance(balance)
                    .build();

        } catch (Exception e) {
            log.debug("Failed to parse transaction block: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate parseDate(String dateStr, DateTimeFormatter formatter) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr.trim(), formatter);
        } catch (Exception e) {
            log.debug("Failed to parse date '{}': {}", dateStr, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseAmount(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(amountStr.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String cleanDescription(String description) {
        if (description == null) return "";
        return description.replaceAll("\\s+", " ").trim();
    }
}
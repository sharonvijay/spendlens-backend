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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PdfParserService {

    private static final DateTimeFormatter DATE_FORMATTER       = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter VALUE_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");

    // Detects the START of a transaction line
    // e.g. "30-04-2026 21:00:44 01 May 2026 210043486118 UPI/DR/..."
    private static final Pattern TXN_START = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})\\s+(\\d+)\\s+(.*)"
    );

    // Extracts amounts from the END of a combined block
    // e.g. "... 33 631.00 47,596.14"  →  group(2)=amount, group(3)=balance
    private static final Pattern AMOUNTS_AT_END = Pattern.compile(
            "\\s+(\\d{1,4})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$"
    );

    // ── Metadata patterns ────────────────────────────────────────────────────
    // FIX: use lazy (.+?) + lookahead so it stops at the next field label.
    // This handles both PDFBox output (newline-separated) and collapsed text
    // (single-line, space-separated) without grabbing the entire rest of the page.
    private static final Pattern HOLDER_PATTERN = Pattern.compile(
            "Account Holders? Name\\s+(.+?)(?=\\s{2,}|[\\r\\n]|Customer Id|$)"
    );
    private static final Pattern ACCOUNT_NO_PATTERN = Pattern.compile(
            "Account Number\\s+(\\d{6,20})"
    );
    private static final Pattern OPENING_BAL_PATTERN = Pattern.compile(
            "Opening Balance\\s+Rs\\.\\s+([\\d,]+\\.\\d{2})"
    );
    private static final Pattern CLOSING_BAL_PATTERN = Pattern.compile(
            "Closing Balance\\s+Rs\\.\\s+([\\d,]+\\.\\d{2})"
    );
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "Searched By From\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})\\s+To\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})"
    );

    // ── Skip lines that must never be accumulated into a transaction block ───
    // "Page N of M" is the primary culprit: it appears after the last
    // transaction on every page and breaks AMOUNTS_AT_END (which anchors to $).
    private static final Pattern SKIP_LINE = Pattern.compile(
            "^(Page\\s+\\d+\\s+of\\s+\\d+|Txn Date|Branch\\s*$|Disclaimer)",
            Pattern.CASE_INSENSITIVE
    );

    // ── UPI vendor slot: text between 3rd and 4th slash in the description ──
    // e.g. UPI/DR/612167032864/ZOMATO LI/YESB/... → "ZOMATO LI"
    private static final Pattern VENDOR_PATTERN = Pattern.compile(
            "UPI/(?:DR|CR)/[^/]+/([^/]+)/"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    public List<Transaction> extractTransactions(InputStream pdfInputStream) throws IOException {
        try (PDDocument document = PDDocument.load(pdfInputStream)) {
            log.info("PDF loaded. Total pages: {}", document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            String pdfText = stripper.getText(document);
            log.info("PDF text extracted. Length: {} characters", pdfText.length());
            List<Transaction> transactions = parseTransactions(pdfText);
            log.info("Extracted {} transactions from PDF", transactions.size());
            return transactions;
        } catch (IOException e) {
            log.error("Error parsing PDF: {}", e.getMessage(), e);
            throw new IOException("Failed to parse PDF statement", e);
        }
    }

    public Map<String, String> extractAccountInfo(InputStream pdfInputStream) throws IOException {
        try (PDDocument document = PDDocument.load(pdfInputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String page1Text = stripper.getText(document);
            return parseAccountInfo(page1Text);
        } catch (IOException e) {
            log.error("Error extracting account info: {}", e.getMessage(), e);
            throw new IOException("Failed to extract account info from PDF", e);
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private List<Transaction> parseTransactions(String pdfText) {
        List<Transaction> transactions = new ArrayList<>();
        String[] lines = pdfText.split("\n");
        List<String> currentBlock = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (TXN_START.matcher(line).find()) {
                if (!currentBlock.isEmpty()) {
                    Transaction txn = parseTransactionBlock(currentBlock);
                    if (txn != null) transactions.add(txn);
                    currentBlock.clear();
                }
                currentBlock.add(line);

            } else if (!currentBlock.isEmpty()) {
                // Skip page footers / column headers — they poison AMOUNTS_AT_END ($)
                if (SKIP_LINE.matcher(line).find()) continue;
                currentBlock.add(line);
            }
        }

        if (!currentBlock.isEmpty()) {
            Transaction txn = parseTransactionBlock(currentBlock);
            if (txn != null) transactions.add(txn);
        }

        return transactions;
    }

    private Transaction parseTransactionBlock(List<String> block) {
        if (block.isEmpty()) return null;

        String combined = String.join(" ", block)
                .replaceAll("\\s+", " ")
                .trim();

        try {
            Matcher startMatcher = TXN_START.matcher(combined);
            if (!startMatcher.find()) return null;

            String txnDateStr   = startMatcher.group(1); // "30-04-2026"
            String valueDateStr = startMatcher.group(2); // "01 May 2026"
            String chequeNo     = startMatcher.group(3); // "210043486118"

            Matcher amountsMatcher = AMOUNTS_AT_END.matcher(combined);
            if (!amountsMatcher.find()) {
                log.warn("Could not extract amounts from: {}",
                        combined.substring(0, Math.min(80, combined.length())));
                return null;
            }

            String amountStr  = amountsMatcher.group(2);
            String balanceStr = amountsMatcher.group(3);

            // Description = everything between chequeNo and the trailing amounts
            int chequeEnd = combined.indexOf(chequeNo) + chequeNo.length();
            String afterCheque = combined.substring(chequeEnd).trim();
            String description = afterCheque
                    .replaceAll("\\s+\\d{1,4}\\s+[\\d,]+\\.\\d{2}\\s+[\\d,]+\\.\\d{2}\\s*$", "")
                    .trim();

            boolean isCredit = description.toUpperCase().contains("UPI/CR/");

            BigDecimal amount  = parseAmount(amountStr);
            BigDecimal balance = parseAmount(balanceStr);
            if (amount.compareTo(BigDecimal.ZERO) == 0) return null;

            return Transaction.builder()
                    .txnDate(parseDate(txnDateStr, DATE_FORMATTER))
                    .valueDate(parseDate(valueDateStr.trim(), VALUE_DATE_FORMATTER))
                    .description(cleanDescription(description))
                    .category(categorize(description))
                    .debit(isCredit  ? BigDecimal.ZERO : amount)
                    .credit(isCredit ? amount : BigDecimal.ZERO)
                    .balance(balance)
                    .build();

        } catch (Exception e) {
            log.debug("Failed to parse transaction block: {}", e.getMessage());
            return null;
        }
    }

    // ── Categorization ────────────────────────────────────────────────────────

    /**
     * Derive a category from the UPI description.
     *
     * UPI descriptions follow a fixed structure:
     *   UPI/DR/{ref}/{VENDOR}/{BANK}/...
     *
     * We extract the vendor slot (between 3rd and 4th slash) and match it
     * against keyword groups. No hardcoded personal names — only merchant
     * keywords that are consistent across all Canara Bank UPI transactions.
     */
    private String categorize(String description) {
        String vendor = extractVendor(description).toUpperCase();

        if (vendor.matches(".*(ZOMATO|SWIGGY|ZEPTO|BLINKIT|DUNZO|SWIGGY|BIGBASKET|GROFERS|JIOMART).*"))
            return "Food & Groceries";

        if (vendor.matches(".*(AMAZON|FLIPKART|MYNTRA|MEESHO|NYKAA|AJIO|LEVIS|SNAPDEAL|SHOPSY).*"))
            return "Shopping";

        if (vendor.matches(".*(NETFLIX|PRIME VID|HOTSTAR|SPOTIFY|MICROSOFT|YOUTUBE|APPLE|MANORAMA|ZEE5|SONYLIV).*"))
            return "Subscriptions";

        if (vendor.matches(".*(REDBUS|IRCTC|MAKEMYTRIP|GOIBIBO|RAPIDO|OLA|UBER|YATRA|ABHIBUS|IXIGO).*"))
            return "Travel";

        if (vendor.matches(".*(RELIANCE|JIO|AIRTEL|BSNL|VODAFONE|AIR FIBER|TATASKY|DISHTV|ACTFIBER).*"))
            return "Utilities & Bills";

        if (vendor.matches(".*(PPF|LIC|SBI LIFE|HDFC LIFE|ICICI PRU|MAX LIFE|BAJAJ ALLIANZ|NPS|MUTUAL).*"))
            return "Investments";

        if (vendor.matches(".*(APOLLO|MEDPLUS|NETMEDS|PRACTO|1MG|PHARMEASY|THYROCARE|LENSKART).*"))
            return "Health & Medical";

        if (vendor.matches(".*(BYJU|UNACADEMY|COURSERA|UDEMY|VEDANTU|WHITEHAT|SIMPLILEARN).*"))
            return "Education";

        if (description.toUpperCase().contains("UPI/CR/"))
            return "Income / Received";

        if (description.toUpperCase().contains("EPF") || description.toUpperCase().contains("PPF"))
            return "Investments";

        return "Others";
    }

    /**
     * Extract the vendor name from a UPI description.
     * "UPI/DR/612167032864/ZOMATO LI/YESB/..." → "ZOMATO LI"
     * Falls back to the full description if the pattern doesn't match
     * (e.g. NEFT, IMPS, cheque transactions).
     */
    private String extractVendor(String description) {
        Matcher m = VENDOR_PATTERN.matcher(description);
        return m.find() ? m.group(1).trim() : description;
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    private Map<String, String> parseAccountInfo(String page1Text) {
        Map<String, String> info = new HashMap<>();

        info.put("accountHolderName", extractGroup(HOLDER_PATTERN,     page1Text, 1, "N/A"));
        info.put("accountNumber",     extractGroup(ACCOUNT_NO_PATTERN,  page1Text, 1, "N/A"));
        info.put("openingBalance",    extractGroup(OPENING_BAL_PATTERN, page1Text, 1, "N/A"));
        info.put("closingBalance",    extractGroup(CLOSING_BAL_PATTERN, page1Text, 1, "N/A"));

        Matcher drm = DATE_RANGE_PATTERN.matcher(page1Text);
        if (drm.find()) {
            info.put("statementFrom", drm.group(1).trim());
            info.put("statementTo",   drm.group(2).trim());
        } else {
            info.put("statementFrom", "N/A");
            info.put("statementTo",   "N/A");
        }

        log.info("Account info extracted: holder={}, account={}, {} to {}",
                info.get("accountHolderName"), info.get("accountNumber"),
                info.get("statementFrom"),     info.get("statementTo"));

        return info;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String extractGroup(Pattern pattern, String text, int group, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(group).trim() : fallback;
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
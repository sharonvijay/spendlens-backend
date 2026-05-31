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
    private static final Pattern TXN_START = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})\\s+(\\d+)\\s+(.*)"
    );

    // Extracts amounts ANYWHERE in the block (removed the $ anchor)
    private static final Pattern AMOUNTS_PATTERN = Pattern.compile(
            "\\b(\\d{1,4})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\b"
    );

    // Extracts Date Range from the header
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "Searched By.*?From\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})\\s+To\\s+(\\d{2}\\s+[A-Za-z]+\\s+\\d{4})"
    );

    // Skip lines that must never be accumulated into a transaction block
    private static final Pattern SKIP_LINE = Pattern.compile(
            "^(Page\\s+\\d+\\s+of\\s+\\d+|Txn Date|Branch|^Code$|Debit Credit Balance|Disclaimer)",
            Pattern.CASE_INSENSITIVE
    );

    // UPI vendor slot: text between 3rd and 4th slash in the description
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
                if (SKIP_LINE.matcher(line).find()){
                    continue;
                }
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

        String combined = String.join(" ", block).replaceAll("\\s+", " ").trim();

        try {
            Matcher startMatcher = TXN_START.matcher(combined);
            if (!startMatcher.find()) return null;

            String txnDateStr   = startMatcher.group(1);
            String valueDateStr = startMatcher.group(2);
            String chequeNo     = startMatcher.group(3);

            Matcher amountsMatcher = AMOUNTS_PATTERN.matcher(combined);
            String amountStr = null;
            String balanceStr = null;
            String matchedAmounts = null;

            // In case the description has random numbers, grab the LAST match in the block
            while (amountsMatcher.find()) {
                matchedAmounts = amountsMatcher.group(0);
                amountStr  = amountsMatcher.group(2);
                balanceStr = amountsMatcher.group(3);
            }

            if (amountStr == null) {
                log.warn("Could not extract amounts from: {}", combined.substring(0, Math.min(80, combined.length())));
                return null;
            }

            int chequeEnd = combined.indexOf(chequeNo) + chequeNo.length();
            String afterCheque = combined.substring(chequeEnd).trim();

            // Clean the description by physically removing the matched amount string
            String description = afterCheque.replace(matchedAmounts, "").trim();
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

    private String categorize(String description) {
        String vendor = extractVendor(description).toUpperCase();

        if (containsAny(vendor, "ZOMATO", "SWIGGY", "ZEPTO", "BLINKIT", "DUNZO", "BIGBASKET", "GROFERS", "JIOMART"))
            return "Food & Groceries";

        if (containsAny(vendor, "AMAZON", "FLIPKART", "MYNTRA", "MEESHO", "NYKAA", "AJIO", "LEVIS", "SNAPDEAL", "SHOPSY"))
            return "Shopping";

        if (containsAny(vendor, "NETFLIX", "PRIME VID", "HOTSTAR", "SPOTIFY", "MICROSOFT", "YOUTUBE", "APPLE", "MANORAMA", "ZEE5", "SONYLIV"))
            return "Subscriptions";

        if (containsAny(vendor, "REDBUS", "IRCTC", "MAKEMYTRIP", "GOIBIBO", "RAPIDO", "OLA", "UBER", "YATRA", "ABHIBUS", "IXIGO"))
            return "Travel";

        if (containsAny(vendor, "RELIANCE", "JIO", "AIRTEL", "BSNL", "VODAFONE", "AIR FIBER", "TATASKY", "DISHTV", "ACTFIBER"))
            return "Utilities & Bills";

        if (containsAny(vendor, "PPF", "LIC", "SBI LIFE", "HDFC LIFE", "ICICI PRU", "MAX LIFE", "BAJAJ ALLIANZ", "NPS", "MUTUAL"))
            return "Investments";

        if (containsAny(vendor, "APOLLO", "MEDPLUS", "NETMEDS", "PRACTO", "1MG", "PHARMEASY", "THYROCARE", "LENSKART"))
            return "Health & Medical";

        if (containsAny(vendor, "BYJU", "UNACADEMY", "COURSERA", "UDEMY", "VEDANTU", "WHITEHAT", "SIMPLILEARN"))
            return "Education";

        String descUpper = description.toUpperCase();

        if (descUpper.contains("UPI/CR/"))
            return "Income / Received";

        if (descUpper.contains("EPF") || descUpper.contains("PPF"))
            return "Investments";

        return "Others";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractVendor(String description) {
        Matcher m = VENDOR_PATTERN.matcher(description);
        return m.find() ? m.group(1).trim() : description;
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    private Map<String, String> parseAccountInfo(String page1Text) {
        Map<String, String> info = new HashMap<>();

        // Flatten the entire header into a single, clean sentence
        String cleanText = page1Text.replaceAll("[\"\r\n,]", " ").replaceAll("\\s+", " ");

        info.put("accountHolder", extractBetween(cleanText, "Account Holders Name", "Customer Id"));
        if (info.get("accountHolder") == null) {
            info.put("accountHolder", extractBetween(cleanText, "Account Holder Name", "Customer Id"));
        }

        info.put("accountNumber", extractBetween(cleanText, "Account Number", "Account Currency"));
        info.put("branchName",    extractBetween(cleanText, "Branch Name", "MICR Code"));

        Matcher drm = DATE_RANGE_PATTERN.matcher(cleanText);
        if (drm.find()) {
            info.put("statementFrom", drm.group(1).trim());
            info.put("statementTo",   drm.group(2).trim());
        } else {
            info.put("statementFrom", "N/A");
            info.put("statementTo",   "N/A");
        }

        return info;
    }

    private String extractBetween(String text, String startLabel, String endLabel) {
        int startIdx = text.indexOf(startLabel);
        if (startIdx == -1) return null;

        int valStart = startIdx + startLabel.length();
        int endIdx = text.indexOf(endLabel, valStart);

        if (endIdx == -1) return text.substring(valStart).trim();
        return text.substring(valStart, endIdx).trim();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

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
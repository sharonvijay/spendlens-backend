package com.spendlens.service;

import com.spendlens.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CategorizationService {

    // Category rules: category name -> regex patterns to match in description
    private final Map<String, Pattern[]> categoryRules;

    public CategorizationService() {
        this.categoryRules = initializeCategoryRules();
    }

    /**
     * Initialize categorization rules
     * Each category has one or more regex patterns to match against description
     */
    private Map<String, Pattern[]> initializeCategoryRules() {
        Map<String, Pattern[]> rules = new HashMap<>();

        // Food & Groceries (Zomato, Swiggy, Zepto, etc.)
        rules.put("Food & Groceries", new Pattern[]{
                Pattern.compile("ZOMATO\\d*", Pattern.CASE_INSENSITIVE),
                Pattern.compile("SWIGGY", Pattern.CASE_INSENSITIVE),
                Pattern.compile("ZEPTO", Pattern.CASE_INSENSITIVE),
                Pattern.compile("BLINKIT", Pattern.CASE_INSENSITIVE),
                Pattern.compile("INSTAMART", Pattern.CASE_INSENSITIVE),
        });

        // Shopping (Amazon, Flipkart, Clothing, etc.)
        rules.put("Shopping", new Pattern[]{
                Pattern.compile("AMAZON", Pattern.CASE_INSENSITIVE),
                Pattern.compile("FLIPKART", Pattern.CASE_INSENSITIVE),
                Pattern.compile("MYNTRA", Pattern.CASE_INSENSITIVE),
                Pattern.compile("LEVIS", Pattern.CASE_INSENSITIVE),
                Pattern.compile("NYKAA", Pattern.CASE_INSENSITIVE),
        });

        // Subscriptions (Netflix, Prime Video, Microsoft, etc.)
        rules.put("Subscriptions", new Pattern[]{
                Pattern.compile("NETFLIX", Pattern.CASE_INSENSITIVE),
                Pattern.compile("PRIME.*VID|AMAZON.*PRIME", Pattern.CASE_INSENSITIVE),
                Pattern.compile("MICROSOFT", Pattern.CASE_INSENSITIVE),
                Pattern.compile("ADOBE", Pattern.CASE_INSENSITIVE),
        });

        // Utilities (Internet, Mobile, Electricity, etc.)
        rules.put("Utilities", new Pattern[]{
                Pattern.compile("RELIANCE|JIO", Pattern.CASE_INSENSITIVE),
                Pattern.compile("AIRTEL|AIR FIBER", Pattern.CASE_INSENSITIVE),
                Pattern.compile("BSNL", Pattern.CASE_INSENSITIVE),
        });

        // Travel (Uber, OLA, RedBus, etc.)
        rules.put("Travel", new Pattern[]{
                Pattern.compile("UBER", Pattern.CASE_INSENSITIVE),
                Pattern.compile("OLA", Pattern.CASE_INSENSITIVE),
                Pattern.compile("REDBUS", Pattern.CASE_INSENSITIVE),
        });

        // Health (Practo, 1MG, Pharmacies, etc.)
        rules.put("Health", new Pattern[]{
                Pattern.compile("PRACTO", Pattern.CASE_INSENSITIVE),
                Pattern.compile("1MG|ONE.*MG", Pattern.CASE_INSENSITIVE),
                Pattern.compile("PHARMACY|MEDICAL", Pattern.CASE_INSENSITIVE),
        });

        // Transfers (Money sent to others)
        rules.put("Transfers", new Pattern[]{
                Pattern.compile("UPI/CR/", Pattern.CASE_INSENSITIVE),  // UPI credit/received
        });

        return rules;
    }

    /**
     * Categorize a single transaction
     */
    public void categorizeTransaction(Transaction transaction) {
        if (transaction == null || transaction.getDescription() == null) {
            transaction.setCategory("Uncategorized");
            transaction.setVendor("Unknown");
            return;
        }

        String description = transaction.getDescription();

        // Try to match against all category rules
        for (Map.Entry<String, Pattern[]> categoryEntry : categoryRules.entrySet()) {
            for (Pattern pattern : categoryEntry.getValue()) {
                if (pattern.matcher(description).find()) {
                    transaction.setCategory(categoryEntry.getKey());
                    transaction.setVendor(extractVendorName(description));
                    log.debug("Categorized '{}' as '{}'", description, categoryEntry.getKey());
                    return;
                }
            }
        }

        // Default category if no match
        transaction.setCategory("Other");
        transaction.setVendor(extractVendorName(description));
    }

    /**
     * Categorize all transactions in a list
     */
    public void categorizeAllTransactions(List<Transaction> transactions) {
        if (transactions == null) {
            return;
        }
        transactions.forEach(this::categorizeTransaction);
    }

    /**
     * Extract vendor name from description
     * Returns the first meaningful word from the description
     * Example: "UPI/DR/ZOMATO/HDFC/..." → "ZOMATO"
     */
    private String extractVendorName(String description) {
        if (description == null || description.isEmpty()) {
            return "Unknown";
        }

        // Split by common delimiters
        String[] parts = description.split("[/\\s,]+");

        for (String part : parts) {
            part = part.trim().toUpperCase();

            // Skip noise (UPI identifiers, numbers, too short)
            if (!part.startsWith("UPI") &&
                    !part.startsWith("DR") &&
                    !part.startsWith("CR") &&
                    !part.matches("\\d+.*") &&
                    part.length() > 2) {
                return part;
            }
        }

        return description.substring(0, Math.min(30, description.length())).toUpperCase();
    }
}
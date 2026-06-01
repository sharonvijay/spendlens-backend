package com.spendlens.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisResponse {

    // ========== Statement Metadata ==========
    private LocalDate statementStartDate;
    private LocalDate statementEndDate;
    private String accountHolder;
    private String accountNumber;
    private String branchName;

    // ========== Overall Summary ==========
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal totalExpenses;
    private BigDecimal totalIncome;
    private Integer transactionCount;

    // Store the AI's 3-sentence summary
    private String aiInsights;

    // ========== Analysis Results ==========
    private Map<String, BigDecimal> categoryBreakdown;
    private List<WeeklyData> weeklyData;
    private List<Transaction> transactions;


    /**
     * Nested class to represent weekly spending data
     * Breaks down spending by weekday (Mon-Fri) vs weekend (Sat-Sun)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WeeklyData {
        private Integer weekNumber;        // Week 1, Week 2, etc.
        private LocalDate weekStartDate;   // Monday of that week
        private LocalDate weekEndDate;     // Sunday of that week
        private BigDecimal weekdaySpend;   // Mon-Fri total
        private BigDecimal weekendSpend;   // Sat-Sun total
        private BigDecimal totalWeekSpend; // Total for the week
        private Integer transactionCount;  // How many txns in this week
    }
}
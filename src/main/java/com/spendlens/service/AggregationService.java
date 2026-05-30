package com.spendlens.service;

import com.spendlens.model.AnalysisResponse;
import com.spendlens.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AggregationService {

    private static final WeekFields WEEK_FIELDS = WeekFields.of(Locale.getDefault());

    /**
     * Aggregate all transaction data into an AnalysisResponse
     */
    public void aggregateData(List<Transaction> transactions, AnalysisResponse response) {
        if (transactions == null || transactions.isEmpty()) {
            log.warn("No transactions to aggregate");
            return;
        }

        // Filter only debit (expense) transactions for analysis
        List<Transaction> expenses = transactions.stream()
                .filter(Transaction::isDebit)
                .collect(Collectors.toList());

        // Calculate totals
        response.setTotalExpenses(calculateTotalExpenses(expenses));
        response.setTotalIncome(calculateTotalIncome(transactions));
        response.setTransactionCount(transactions.size());

        // Category breakdown (sorted by highest spending)
        response.setCategoryBreakdown(calculateCategoryBreakdown(expenses));

        // Weekly breakdown with weekday/weekend splits
        response.setWeeklyData(calculateWeeklyData(expenses));

        log.info("Aggregation complete: {} expenses, {} income, {} categories",
                response.getTotalExpenses(), response.getTotalIncome(),
                response.getCategoryBreakdown().size());
    }

    /**
     * Calculate total expenses (sum of all debits)
     */
    private BigDecimal calculateTotalExpenses(List<Transaction> expenses) {
        return expenses.stream()
                .map(Transaction::getDebit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculate total income (sum of all credits)
     */
    private BigDecimal calculateTotalIncome(List<Transaction> transactions) {
        return transactions.stream()
                .filter(Transaction::isCredit)
                .map(Transaction::getCredit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculate spending by category
     * Returns map: Category → Total Amount (sorted by highest first)
     */
    private Map<String, BigDecimal> calculateCategoryBreakdown(List<Transaction> expenses) {
        return expenses.stream()
                .filter(t -> t.getCategory() != null)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                Transaction::getDebit,
                                BigDecimal::add
                        )
                ))
                .entrySet().stream()
                .filter(e -> e.getValue().compareTo(BigDecimal.ZERO) > 0)
                // Sort by highest spending first
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new  // Preserve order
                ));
    }

    /**
     * Calculate weekly spending breakdown
     * Split each week into:
     * - Weekday (Tue, Wed, Thu, Fri)
     * - Weekend (Sat, Sun, Mon - your WFH day)
     */
    private List<AnalysisResponse.WeeklyData> calculateWeeklyData(List<Transaction> expenses) {
        if (expenses.isEmpty()) {
            return Collections.emptyList();
        }

        // Group transactions by week
        Map<Integer, List<Transaction>> weekGroups = expenses.stream()
                .filter(t -> t.getTxnDate() != null)
                .collect(Collectors.groupingBy(t -> t.getTxnDate().get(WEEK_FIELDS.weekOfWeekBasedYear())));

        // Convert to WeeklyData objects
        return weekGroups.entrySet().stream()
                .map(entry -> createWeeklyData(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(AnalysisResponse.WeeklyData::getWeekNumber))
                .collect(Collectors.toList());
    }

    /**
     * Create a WeeklyData object from a list of transactions for that week
     * Remember: Weekday = Tue, Wed, Thu, Fri
     *           Weekend = Sat, Sun, Mon (your WFH day)
     */
    private AnalysisResponse.WeeklyData createWeeklyData(int weekNumber, List<Transaction> weekTransactions) {

        // Find the Monday and Sunday of this week
        LocalDate firstTransactionDate = weekTransactions.stream()
                .map(Transaction::getTxnDate)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());

        // Calculate Monday of this week
        LocalDate weekStart = firstTransactionDate.with(ChronoField.DAY_OF_WEEK, 1); // Monday
        LocalDate weekEnd = weekStart.plusDays(6);  // Sunday

        // Split transactions into weekday vs weekend
        BigDecimal weekdaySpend = BigDecimal.ZERO;  // Tue, Wed, Thu, Fri
        BigDecimal weekendSpend = BigDecimal.ZERO;  // Sat, Sun, Mon

        for (Transaction t : weekTransactions) {
            DayOfWeek dayOfWeek = t.getTxnDate().getDayOfWeek();
            BigDecimal amount = t.getDebit();

            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                // Check if it's a weekend day (Sat, Sun) or Monday (WFH)
                if (dayOfWeek == DayOfWeek.SATURDAY ||
                        dayOfWeek == DayOfWeek.SUNDAY ||
                        dayOfWeek == DayOfWeek.MONDAY) {

                    weekendSpend = weekendSpend.add(amount);

                } else {
                    // Weekday (Tue, Wed, Thu, Fri)
                    weekdaySpend = weekdaySpend.add(amount);
                }
            }
        }

        BigDecimal totalWeekSpend = weekdaySpend.add(weekendSpend);

        return AnalysisResponse.WeeklyData.builder()
                .weekNumber(weekNumber)
                .weekStartDate(weekStart)
                .weekEndDate(weekEnd)
                .weekdaySpend(weekdaySpend)        // Tue-Fri
                .weekendSpend(weekendSpend)        // Sat, Sun, Mon
                .totalWeekSpend(totalWeekSpend)
                .transactionCount(weekTransactions.size())
                .build();
    }
}
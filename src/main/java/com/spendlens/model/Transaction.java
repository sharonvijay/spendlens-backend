package com.spendlens.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    private LocalDate txnDate;
    private LocalDate valueDate;
    private String description;
    private BigDecimal debit;
    private BigDecimal credit;
    private BigDecimal balance;
    private String category;
    private String vendor;

    /**
     * Get the transaction amount (absolute value)
     */
    public BigDecimal getAmount() {
        if (debit != null && debit.compareTo(BigDecimal.ZERO) > 0) {
            return debit;
        }
        if (credit != null && credit.compareTo(BigDecimal.ZERO) > 0) {
            return credit;
        }
        return BigDecimal.ZERO;
    }

    /**
     * Check if this is an expense (debit)
     */
    public boolean isDebit() {
        return debit != null && debit.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if this is income (credit)
     */
    public boolean isCredit() {
        return credit != null && credit.compareTo(BigDecimal.ZERO) > 0;
    }
}
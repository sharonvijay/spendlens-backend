package com.spendlens.dto;

import com.spendlens.model.Transaction;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    private String question;
    private List<Transaction> transactions;
}

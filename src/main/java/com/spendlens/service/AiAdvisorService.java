package com.spendlens.service;

import com.spendlens.model.AnalysisResponse;
import com.spendlens.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiAdvisorService {

    private final ChatClient chatClient;

    public AiAdvisorService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Generate the Financial Insights Summary
     * Data Masking: We ONLY pass the category names and totals. No names or account numbers.
     */
    public String generateFinancialInsights(AnalysisResponse response) {
        log.info("Generating AI Financial Insights...");

        try {
            // 1. Build a completely anonymous data string
            String anonymousData = String.format(
                    "Total Expenses: ₹%s\nTotal Income: ₹%s\nCategory Breakdown: %s",
                    response.getTotalExpenses(),
                    response.getTotalIncome(),
                    response.getCategoryBreakdown().toString()
            );

            // 2. Ask Gemini for the summary
            return chatClient.prompt()
                    .system("You are an expert, encouraging financial advisor. " +
                            "Analyze the provided monthly spending data. " +
                            "Keep your response to exactly 3 short, punchy sentences. " +
                            "Highlight one area they spent a lot on, and give one actionable tip.")
                    .user(anonymousData)
                    .call()
                    .content();

        } catch (Exception e) {
            log.error("Failed to generate AI insights: {}", e.getMessage());
            return "Your financial data has been successfully processed. Consider reviewing your top spending categories this month to find potential savings!";
        }
    }

    /**
     * The Chat Copilot
     * Data Masking: We map the transactions to a safe string, stripping out any potential PII.
     */
    public String answerUserQuestion(String question, List<Transaction> transactions) {
        log.info("Answering user chat question: {}", question);
        List<Transaction> relevantTransactions = transactions.size() > 30
                ? transactions.subList(transactions.size() - 30, transactions.size())
                : transactions;

        try {
            // 1. Build a safe, lightweight string of transactions (Date, Vendor, Category, Amount)
            String safeTransactionList = relevantTransactions.stream()
                    .map(t -> String.format("%s | %s | %s | ₹%s",
                            t.getTxnDate(),
                            t.getVendor() != null ? t.getVendor() : "Unknown",
                            t.getCategory() != null ? t.getCategory() : "Uncategorized",
                            t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO))
                    .collect(Collectors.joining("\n"));

            // 2. Ask Gemini to act as a data analyst
            return chatClient.prompt()
                    .system("You are a helpful AI financial assistant for the SpendLens app. " +
                            "You must answer the user's question using ONLY the provided transaction data. " +
                            "If the answer is not in the data, say 'I cannot find that in your recent transactions.' " +
                            "Be concise and conversational.")
                    .user(u -> u.text("User Question: {question}\n\nTransaction Data:\n{data}")
                            .param("question", question)
                            .param("data", safeTransactionList))
                    .call()
                    .content();

        } catch (Exception e) {
            log.error("Failed to answer chat question: {}", e.getMessage());
            return "I'm sorry, I'm having trouble analyzing your transactions right now.";
        }
    }
}

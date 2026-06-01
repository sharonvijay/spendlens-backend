package com.spendlens.controller;

import com.spendlens.dto.ChatRequest;
import com.spendlens.model.AnalysisResponse;
import com.spendlens.service.AiAdvisorService;
import com.spendlens.service.StatementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)  // Allow CORS for Angular frontend
public class StatementController {

    private final StatementService statementService;
    private final AiAdvisorService aiAdvisorService;

    /**
     * POST /api/v1/statements/analyze
     *
     * Upload a Canara Bank PDF statement and get analysis
     *
     * Request: Multipart file upload (PDF)
     * Response: JSON AnalysisResponse with transactions, totals, categories, weekly data
     *
     * Example:
     *   curl -X POST http://localhost:8080/api/v1/statements/analyze \
     *        -F "file=@statement.pdf"
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.Operation(summary = "Analyze bank statement PDF")
    public ResponseEntity<AnalysisResponse> analyzeStatement(
            @io.swagger.v3.oas.annotations.Parameter(
                    description = "Canara Bank PDF Statement",
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    type = "string",
                                    format = "binary"
                            )
                    )
            )
            @RequestParam("file") MultipartFile file) {

        log.info("📥 Received statement upload: {}", file.getOriginalFilename());

        try {
            AnalysisResponse analysis = statementService.analyzeStatement(file);
            log.info("✅ Statement analyzed successfully");
            return ResponseEntity.ok(analysis);

        } catch (IOException e) {
            log.error("❌ Error processing statement: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();

        } catch (Exception e) {
            log.error("❌ Unexpected error during analysis: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * POST /api/v1/statements/export
     *
     * Generate and download Excel report
     *
     * Request: JSON AnalysisResponse (sent back from analyze endpoint)
     * Response: .xlsx file as byte array (downloadable)
     *
     * Example:
     *   curl -X POST http://localhost:8080/api/v1/statements/export \
     *        -H "Content-Type: application/json" \
     *        -d @analysis.json \
     *        -o report.xlsx
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportToExcel(
            @RequestBody AnalysisResponse analysis) {

        log.info("📊 Received Excel export request");

        try {
            // Generate Excel file
            byte[] excelContent = statementService.getExcelExport(analysis);

            // Create filename with timestamp
            String filename = "SpendLens_Report_" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) +
                    ".xlsx";

            // Set response headers for download
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", filename);

            log.info("✅ Excel export generated: {}", filename);
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(excelContent);

        } catch (IOException e) {
            log.error("❌ Error generating Excel export: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * POST /api/v1/statements/chat
     *
     * Interactive AI chat over the analyzed transactions
     */
    @PostMapping("/chat")
    public ResponseEntity<java.util.Map<String, String>> chatWithStatement(@RequestBody ChatRequest request) {
        log.info("💬 Received chat request");

        String aiResponse = aiAdvisorService.answerUserQuestion(
                request.getQuestion(),
                request.getTransactions()
        );

        return ResponseEntity.ok(java.util.Map.of("reply", aiResponse));
    }

    /**
     * POST /api/v1/statements/insights
     *
     * Generate AI insights on demand after analysis loads
     */
    @PostMapping("/insights")
    public ResponseEntity<java.util.Map<String, String>> generateInsights(@RequestBody AnalysisResponse analysis) {
        log.info("✨ Received AI insights request");

        String aiResponse = aiAdvisorService.generateFinancialInsights(analysis);
        return ResponseEntity.ok(java.util.Map.of("reply", aiResponse));
    }

    /**
     * GET /api/v1/statements/health
     *
     * Health check endpoint
     * Verify backend is running
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        log.info("🏥 Health check requested");
        return ResponseEntity.ok("✅ SpendLens backend is running");
    }
}

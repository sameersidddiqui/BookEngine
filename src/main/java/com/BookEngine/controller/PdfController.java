package com.BookEngine.controller;

import com.BookEngine.dto.PdfExtractionResponse;
import com.BookEngine.service.PdfExtractionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PdfController {

	private final PdfExtractionService extractionService;

	public PdfController(PdfExtractionService extractionService) {
		this.extractionService = extractionService;
	}

	@PostMapping(path = "/api/v1/pdfs/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public PdfExtractionResponse extract(@RequestParam(name = "file", required = false) MultipartFile file) {
		return this.extractionService.extract(file);
	}

	@GetMapping("/api/v1/pdfs/health")
	public HealthResponse health() {
		return new HealthResponse("UP");
	}

	public record HealthResponse(String status) {
	}
}

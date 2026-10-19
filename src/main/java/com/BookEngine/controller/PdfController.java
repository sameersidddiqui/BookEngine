package com.BookEngine.controller;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import com.BookEngine.dto.PdfLibraryDocumentResponse;
import com.BookEngine.dto.PdfLibraryItemResponse;
import com.BookEngine.dto.PdfLibraryRenameRequest;
import com.BookEngine.dto.PdfReadingProgressRequest;
import com.BookEngine.dto.PdfExtractionResponse;
import com.BookEngine.service.PdfExtractionService;
import com.BookEngine.service.PdfLibraryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PdfController {

	private static final String LIBRARY_COOKIE = "iqra_library";
	private static final String LIBRARY_PATH = "/api/v1/pdfs/library";
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final PdfExtractionService extractionService;
	private final PdfLibraryService libraryService;

	public PdfController(PdfExtractionService extractionService, PdfLibraryService libraryService) {
		this.extractionService = extractionService;
		this.libraryService = libraryService;
	}

	@PostMapping(path = "/api/v1/pdfs/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public PdfExtractionResponse extract(@RequestParam(name = "file", required = false) MultipartFile file) {
		return this.extractionService.extract(file);
	}

	@GetMapping("/api/v1/pdfs/health")
	public HealthResponse health() {
		return new HealthResponse("UP");
	}

	@PostMapping("/api/v1/pdfs/library/session")
	public ResponseEntity<Void> initializeLibrarySession(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId,
			HttpServletRequest request) {
		if (PdfLibraryService.isValidSessionId(sessionId)) {
			return ResponseEntity.noContent().build();
		}
		byte[] token = new byte[32];
		SECURE_RANDOM.nextBytes(token);
		String newSessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
		ResponseCookie cookie = ResponseCookie.from(LIBRARY_COOKIE, newSessionId)
				.httpOnly(true)
				.secure(request.isSecure())
				.sameSite("Strict")
				.path(LIBRARY_PATH)
				.maxAge(Duration.ofDays(365))
				.build();
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
	}

	@GetMapping("/api/v1/pdfs/library")
	public List<PdfLibraryItemResponse> library(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId) {
		return this.libraryService.list(sessionId);
	}

	@PostMapping(path = "/api/v1/pdfs/library", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public PdfLibraryDocumentResponse save(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId,
			@RequestParam(name = "file", required = false) MultipartFile file) {
		return this.libraryService.save(sessionId, file);
	}

	@GetMapping("/api/v1/pdfs/library/{id}")
	public PdfLibraryDocumentResponse open(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId,
			@PathVariable String id) {
		return this.libraryService.open(sessionId, id);
	}

	@PutMapping("/api/v1/pdfs/library/{id}")
	public PdfLibraryItemResponse rename(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId,
			@PathVariable String id,
			@RequestBody PdfLibraryRenameRequest request) {
		return this.libraryService.rename(sessionId, id, request.fileName());
	}

	@PutMapping("/api/v1/pdfs/library/{id}/progress")
	public PdfLibraryItemResponse updateProgress(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId,
			@PathVariable String id,
			@RequestBody PdfReadingProgressRequest request) {
		return this.libraryService.updateProgress(sessionId, id, request.page(), request.progress());
	}

	@DeleteMapping("/api/v1/pdfs/library/{id}")
	public ResponseEntity<Void> delete(
			@CookieValue(name = LIBRARY_COOKIE, required = false) String sessionId,
			@PathVariable String id) {
		this.libraryService.delete(sessionId, id);
		return ResponseEntity.noContent().build();
	}

	public record HealthResponse(String status) {
	}
}

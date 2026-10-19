package com.BookEngine.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import com.BookEngine.dto.PdfExtractionResponse;
import com.BookEngine.dto.PdfLibraryDocumentResponse;
import com.BookEngine.dto.PdfLibraryItemResponse;
import com.BookEngine.extractor.PdfExtractionException;
import com.BookEngine.validation.PdfFileValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfLibraryService {

	private static final Pattern DOCUMENT_ID = Pattern.compile(
			"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	private static final Pattern SAFE_SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{40,50}$");

	private final PdfFileValidator validator;
	private final PdfExtractionService extractionService;
	private final PdfLibraryStorageService storage;

	public PdfLibraryService(
			PdfFileValidator validator,
			PdfExtractionService extractionService,
			PdfLibraryStorageService storage) {
		this.validator = validator;
		this.extractionService = extractionService;
		this.storage = storage;
	}

	public List<PdfLibraryItemResponse> list(String sessionId) {
		return this.storage.list(ownerKey(sessionId));
	}

	public PdfLibraryDocumentResponse save(String sessionId, MultipartFile file) {
		this.validator.validate(file);
		try {
			byte[] content = file.getBytes();
			PdfExtractionResponse extraction = this.extractionService.extract(content, file.getOriginalFilename());
			PdfLibraryStorageService.StoredDocument document = this.storage.save(
					ownerKey(sessionId), extraction.fileName(), content, extraction.pageCount());
			return response(document, extraction);
		}
		catch (java.io.IOException exception) {
			throw new IllegalStateException("Unable to read the uploaded file.", exception);
		}
	}

	public PdfLibraryDocumentResponse open(String sessionId, String id) {
		PdfLibraryStorageService.StoredDocument document = this.storage.find(ownerKey(sessionId), checkedId(id));
		byte[] content = this.storage.read(document);
		PdfExtractionResponse extraction = this.extractionService.extract(content, document.fileName());
		return response(document, extraction);
	}

	public PdfLibraryItemResponse rename(String sessionId, String id, String requestedName) {
		if (requestedName == null) {
			throw new InvalidPdfLibraryRequestException("A document name is required.");
		}
		String fileName = requestedName.strip();
		if (fileName.isEmpty() || fileName.length() > 200 || fileName.matches(".*[\\\\/\\p{Cntrl}].*")) {
			throw new InvalidPdfLibraryRequestException("Choose a name between 1 and 200 characters without path separators.");
		}
		if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
			fileName += ".pdf";
		}
		PdfLibraryStorageService.StoredDocument document = this.storage.rename(
				ownerKey(sessionId), checkedId(id), fileName);
		return this.storage.list(document.ownerKey()).stream()
				.filter(item -> item.documentId().equals(document.id()))
				.findFirst()
				.orElseThrow();
	}

	public PdfLibraryItemResponse updateProgress(String sessionId, String id, int page, int progress) {
		PdfLibraryStorageService.StoredDocument current = this.storage.find(ownerKey(sessionId), checkedId(id));
		int maximumPage = Math.max(1, current.pageCount());
		if (page < 1 || page > maximumPage || progress < 0 || progress > 100) {
			throw new InvalidPdfLibraryRequestException("Reading progress is outside the document's range.");
		}
		PdfLibraryStorageService.StoredDocument document = this.storage.updateProgress(
				current.ownerKey(), current.id(), page, progress);
		return this.storage.list(document.ownerKey()).stream()
				.filter(item -> item.documentId().equals(document.id()))
				.findFirst()
				.orElseThrow();
	}

	public void delete(String sessionId, String id) {
		this.storage.delete(ownerKey(sessionId), checkedId(id));
	}

	public static boolean isValidSessionId(String sessionId) {
		return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches();
	}

	private PdfLibraryDocumentResponse response(
			PdfLibraryStorageService.StoredDocument document, PdfExtractionResponse extraction) {
		return new PdfLibraryDocumentResponse(
				document.id(), document.fileName(), document.fileSize(), extraction.pageCount(),
				extraction.metadata(), extraction.text(), extraction.blocks(), document.lastReadPage(),
				document.lastReadProgress(), document.lastReadAt());
	}

	private String checkedId(String id) {
		if (id == null || !DOCUMENT_ID.matcher(id).matches()) {
			throw new PdfLibraryDocumentNotFoundException();
		}
		return id;
	}

	private String ownerKey(String sessionId) {
		if (!isValidSessionId(sessionId)) {
			throw new PdfLibraryAccessDeniedException();
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(sessionId.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}
}

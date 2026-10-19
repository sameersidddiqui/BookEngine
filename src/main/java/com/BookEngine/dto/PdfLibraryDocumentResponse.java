package com.BookEngine.dto;

import java.time.Instant;
import java.util.List;

public record PdfLibraryDocumentResponse(
		String documentId,
		String fileName,
		long fileSize,
		int pageCount,
		PdfMetadataResponse metadata,
		String text,
		List<PdfTextBlock> blocks,
		int lastReadPage,
		int lastReadProgress,
		Instant lastReadAt) {
}

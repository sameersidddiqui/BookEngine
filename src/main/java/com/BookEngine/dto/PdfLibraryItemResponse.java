package com.BookEngine.dto;

import java.time.Instant;

public record PdfLibraryItemResponse(
		String documentId,
		String fileName,
		long fileSize,
		int pageCount,
		int lastReadPage,
		int lastReadProgress,
		Instant lastReadAt,
		Instant updatedAt,
		boolean available) {
}

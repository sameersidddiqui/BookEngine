package com.BookEngine.dto;

import java.util.List;

public record PdfExtractionResponse(
		String fileName,
		int pageCount,
		PdfMetadataResponse metadata,
		String text,
		List<PdfTextBlock> blocks) {
}

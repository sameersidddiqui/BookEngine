package com.BookEngine.extractor;

import java.util.List;

import com.BookEngine.dto.PdfTextBlock;

public record PdfExtractionResult(
		int pageCount,
		String title,
		String author,
		String subject,
		String creator,
		String producer,
		String text,
		List<PdfTextBlock> blocks) {
}

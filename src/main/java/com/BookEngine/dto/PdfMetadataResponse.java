package com.BookEngine.dto;

public record PdfMetadataResponse(
		String title,
		String author,
		String subject,
		String creator,
		String producer) {
}

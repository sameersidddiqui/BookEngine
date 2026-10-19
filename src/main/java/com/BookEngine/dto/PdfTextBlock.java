package com.BookEngine.dto;

public record PdfTextBlock(String type, String text, int pageNumber, int endPageNumber) {

	public PdfTextBlock(String type, String text) {
		this(type, text, 1, 1);
	}

	public PdfTextBlock(String type, String text, int pageNumber) {
		this(type, text, pageNumber, pageNumber);
	}
}

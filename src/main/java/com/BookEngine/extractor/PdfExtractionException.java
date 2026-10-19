package com.BookEngine.extractor;

public class PdfExtractionException extends RuntimeException {

	public PdfExtractionException(Throwable cause) {
		super("The uploaded file is not a readable PDF or is password-protected.", cause);
	}
}

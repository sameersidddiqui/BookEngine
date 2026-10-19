package com.BookEngine.validation;

public class PdfFileTooLargeException extends RuntimeException {

	public PdfFileTooLargeException() {
		super("The uploaded file exceeds the maximum allowed size.");
	}
}

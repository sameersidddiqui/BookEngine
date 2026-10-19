package com.BookEngine.validation;

public class InvalidPdfUploadException extends RuntimeException {

	public InvalidPdfUploadException(String message) {
		super(message);
	}
}

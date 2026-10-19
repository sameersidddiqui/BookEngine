package com.BookEngine.service;

public class InvalidPdfLibraryRequestException extends RuntimeException {

	public InvalidPdfLibraryRequestException(String message) {
		super(message);
	}
}

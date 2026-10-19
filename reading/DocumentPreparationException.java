package com.BookEngine.reading;

public class DocumentPreparationException extends RuntimeException {

	private final String errorCode;

	public DocumentPreparationException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public DocumentPreparationException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return this.errorCode;
	}
}

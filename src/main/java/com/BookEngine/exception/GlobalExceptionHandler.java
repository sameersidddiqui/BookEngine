package com.BookEngine.exception;

import com.BookEngine.dto.ApiErrorResponse;
import com.BookEngine.extractor.PdfExtractionException;
import com.BookEngine.service.InvalidPdfLibraryRequestException;
import com.BookEngine.service.PdfLibraryAccessDeniedException;
import com.BookEngine.service.PdfLibraryDocumentNotFoundException;
import com.BookEngine.validation.InvalidPdfUploadException;
import com.BookEngine.validation.PdfFileTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler({InvalidPdfUploadException.class, PdfExtractionException.class})
	public ResponseEntity<ApiErrorResponse> handleInvalidPdf(Exception exception) {
		return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_pdf", exception.getMessage()));
	}

	@ExceptionHandler({PdfFileTooLargeException.class, MaxUploadSizeExceededException.class})
	public ResponseEntity<ApiErrorResponse> handleFileTooLarge(Exception exception) {
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
				.body(new ApiErrorResponse("file_too_large", "The uploaded file exceeds the maximum allowed size."));
	}

	@ExceptionHandler(MultipartException.class)
	public ResponseEntity<ApiErrorResponse> handleMultipartError(MultipartException exception) {
		return ResponseEntity.badRequest()
				.body(new ApiErrorResponse("invalid_multipart_request", "The multipart upload could not be read."));
	}

	@ExceptionHandler(InvalidPdfLibraryRequestException.class)
	public ResponseEntity<ApiErrorResponse> handleInvalidLibraryRequest(InvalidPdfLibraryRequestException exception) {
		return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_request", exception.getMessage()));
	}

	@ExceptionHandler(PdfLibraryDocumentNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleMissingLibraryDocument() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiErrorResponse("document_not_found", "This saved PDF is no longer available."));
	}

	@ExceptionHandler(PdfLibraryAccessDeniedException.class)
	public ResponseEntity<ApiErrorResponse> handleMissingLibrarySession() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new ApiErrorResponse("library_session_required", "Initialize the PDF library session first."));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpectedError(Exception exception) {
		logger.error("Unexpected error while processing a PDF request.", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiErrorResponse("internal_server_error", "An unexpected error occurred."));
	}
}

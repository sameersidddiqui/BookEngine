package com.BookEngine.validation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PdfFileValidator {

	private final long maximumFileSize;

	public PdfFileValidator(@Value("${app.upload.max-file-size:10MB}") DataSize maximumFileSize) {
		this.maximumFileSize = maximumFileSize.toBytes();
	}

	public void validate(MultipartFile file) {
		if (file == null) {
			throw new InvalidPdfUploadException("A PDF file is required.");
		}
		if (file.isEmpty()) {
			throw new InvalidPdfUploadException("The uploaded file must not be empty.");
		}
		if (file.getSize() > this.maximumFileSize) {
			throw new PdfFileTooLargeException();
		}
	}
}

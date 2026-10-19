package com.BookEngine.service;

import java.io.IOException;

import com.BookEngine.dto.PdfExtractionResponse;
import com.BookEngine.dto.PdfMetadataResponse;
import com.BookEngine.extractor.PdfExtractionResult;
import com.BookEngine.extractor.PdfTextExtractor;
import com.BookEngine.validation.PdfFileValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfExtractionService {

	private final PdfFileValidator validator;
	private final PdfTextExtractor extractor;

	public PdfExtractionService(PdfFileValidator validator, PdfTextExtractor extractor) {
		this.validator = validator;
		this.extractor = extractor;
	}

	public PdfExtractionResponse extract(MultipartFile file) {
		this.validator.validate(file);

		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to read the uploaded file.", exception);
		}

		PdfExtractionResult result = this.extractor.extract(content);
		return new PdfExtractionResponse(
				fileName(file.getOriginalFilename()),
				result.pageCount(),
				new PdfMetadataResponse(
						result.title(),
						result.author(),
						result.subject(),
						result.creator(),
						result.producer()),
				result.text(),
				result.blocks());
	}

	private String fileName(String originalFilename) {
		if (originalFilename == null) {
			return null;
		}

		String normalizedFilename = originalFilename.replace('\\', '/');
		return normalizedFilename.substring(normalizedFilename.lastIndexOf('/') + 1);
	}
}

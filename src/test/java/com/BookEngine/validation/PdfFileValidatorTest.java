package com.BookEngine.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class PdfFileValidatorTest {

	private final PdfFileValidator validator = new PdfFileValidator(DataSize.ofBytes(8));

	@Test
	void rejectsMissingFile() {
		assertThrows(InvalidPdfUploadException.class, () -> this.validator.validate(null));
	}

	@Test
	void rejectsEmptyFile() {
		MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

		assertThrows(InvalidPdfUploadException.class, () -> this.validator.validate(file));
	}

	@Test
	void rejectsFileAboveConfiguredSize() {
		MockMultipartFile file = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[9]);

		assertThrows(PdfFileTooLargeException.class, () -> this.validator.validate(file));
	}

	@Test
	void doesNotTrustFilenameOrContentTypeAsPdfValidation() {
		MockMultipartFile file = new MockMultipartFile("file", "upload.bin", "text/plain", new byte[] { 1 });

		assertDoesNotThrow(() -> this.validator.validate(file));
	}
}

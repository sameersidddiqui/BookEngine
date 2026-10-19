package com.BookEngine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import com.BookEngine.PdfTestFixtures;
import com.BookEngine.dto.PdfExtractionResponse;
import com.BookEngine.extractor.PdfExtractionException;
import com.BookEngine.extractor.PdfTextExtractor;
import com.BookEngine.validation.PdfFileValidator;
import com.BookEngine.validation.InvalidPdfUploadException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class PdfExtractionServiceTest {

	private final PdfExtractionService service =
			new PdfExtractionService(new PdfFileValidator(DataSize.ofKilobytes(10)), new PdfTextExtractor());

	@Test
	void validatesExtractsAndMapsTheResponse() throws IOException {
		MockMultipartFile file = new MockMultipartFile(
				"file", "example.pdf", "application/pdf", PdfTestFixtures.pdfWithText("Service text"));

		PdfExtractionResponse response = this.service.extract(file);

		assertEquals("example.pdf", response.fileName());
		assertEquals(1, response.pageCount());
		assertEquals("Example title", response.metadata().title());
		assertTrue(response.text().contains("Service text"));
	}

	@Test
	void rejectsMissingUploadBeforeCallingTheExtractor() {
		assertThrows(InvalidPdfUploadException.class, () -> this.service.extract(null));
	}

	@Test
	void reportsUnreadablePdfAsAnExtractionError() {
		MockMultipartFile file = new MockMultipartFile("file", "bad.pdf", "application/pdf", new byte[] { 1, 2, 3 });

		assertThrows(PdfExtractionException.class, () -> this.service.extract(file));
	}
}

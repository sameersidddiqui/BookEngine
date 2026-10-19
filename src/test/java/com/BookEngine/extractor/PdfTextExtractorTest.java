package com.BookEngine.extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import com.BookEngine.PdfTestFixtures;
import org.junit.jupiter.api.Test;

class PdfTextExtractorTest {

	private final PdfTextExtractor extractor = new PdfTextExtractor();

	@Test
	void extractsTextPageCountAndMetadata() throws IOException {
		PdfExtractionResult result = this.extractor.extract(PdfTestFixtures.pdfWithText("First page", "Second page"));

		assertEquals(2, result.pageCount());
		assertTrue(result.text().contains("First page"));
		assertTrue(result.text().contains("Second page"));
		assertEquals("Example title", result.title());
		assertEquals("Example author", result.author());
		assertEquals("Example subject", result.subject());
		assertEquals("Example creator", result.creator());
		assertEquals("Example producer", result.producer());
	}

	@Test
	void normalizesLinesAndDetectsStructuredBlocksAndRepeatedPageFurniture() throws IOException {
		PdfExtractionResult result = this.extractor.extract(PdfTestFixtures.structuredPdf());

		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("h2") && block.text().equals("Chapter One")));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("p") && block.text().contains("sentence can cross a hyphenated line here.")));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("ul") && block.text().equals("First item\nSecond item")));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("ol") && block.text().equals("First step\nSecond step")));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("blockquote") && block.text().contains("A quoted passage. Another quoted line.")));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("table") && block.text().contains("Name\tCount\tStatus")), result.blocks().toString());
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("code") && block.text().contains("process(i);") && block.text().contains("\n")));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("footnote") && block.text().contains("small-print note")));
		assertTrue(result.text().indexOf("Left column first.") < result.text().indexOf("Left column second."));
		assertTrue(result.text().indexOf("Left column second.") < result.text().indexOf("Right column first."),
				result.text());
		assertTrue(result.text().indexOf("Right column first.") < result.text().indexOf("Right column second."));
		assertTrue(result.text().contains("A separate paragraph."));
		assertEquals(result.text().indexOf("A duplicate at the same location."),
				result.text().lastIndexOf("A duplicate at the same location."));
		assertFalse(result.text().contains("Repeated book header"));
		assertFalse(result.text().contains("Repeated book footer"));
		assertTrue(result.blocks().stream().allMatch(block ->
				List.of("p", "h2", "ul", "ol", "blockquote", "table", "code", "footnote").contains(block.type())));
	}

	@Test
	void acceptsAnEmptyPdfDocument() throws IOException {
		PdfExtractionResult result = this.extractor.extract(PdfTestFixtures.emptyPdf());

		assertEquals(0, result.pageCount());
		assertEquals("", result.text());
	}

	@Test
	void rejectsMalformedPdfContent() {
		assertThrows(PdfExtractionException.class, () -> this.extractor.extract("not a PDF".getBytes()));
	}

	@Test
	void rejectsEncryptedPdfWithoutPassword() throws IOException {
		assertThrows(PdfExtractionException.class, () -> this.extractor.extract(PdfTestFixtures.encryptedPdf()));
	}
}

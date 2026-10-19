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
		assertTrue(result.blocks().stream().anyMatch(block -> block.pageNumber() == 1));
		assertTrue(result.blocks().stream().anyMatch(block -> block.pageNumber() == 2));
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
				block.type().equals("p") && block.text().contains("A quoted passage. Another quoted line.")));
		assertFalse(result.blocks().stream().anyMatch(block -> block.type().equals("blockquote")));
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
				List.of("p", "h1", "h2", "h3", "toc", "ul", "ol", "blockquote", "table", "code", "footnote")
						.contains(block.type())));
	}

	@Test
	void reconstructsBookParagraphsContentsHeadingsAndPageAnchors() throws IOException {
		PdfExtractionResult result = this.extractor.extract(PdfTestFixtures.bookLikePdf());

		assertTrue(result.blocks().stream().anyMatch(block -> block.type().equals("toc")
				&& block.text().equals("Chapter 1 The Beginning — 2") && block.pageNumber() == 1), result.blocks().toString());
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("h1") && block.text().equals("PART I") && block.pageNumber() == 2));
		assertTrue(result.blocks().stream().anyMatch(block ->
				block.type().equals("h2") && block.text().equals("I BEGINNINGS") && block.pageNumber() == 2),
				result.blocks().toString());
		assertTrue(result.blocks().stream().anyMatch(block -> block.type().equals("p")
				&& block.text().equals("A continuous book paragraph starts here and continues on the next visual line.")),
				result.blocks().toString());
		assertTrue(result.blocks().stream().anyMatch(block -> block.type().equals("p")
				&& block.text().equals("This sentence continues at the top of the next page after crossing a page boundary.")
				&& block.pageNumber() == 2 && block.endPageNumber() == 3), result.blocks().toString());
		assertTrue(result.blocks().stream().anyMatch(block -> block.type().equals("p")
				&& block.text().equals("Well-known compounds stay hyphenated.")), result.blocks().toString());
		assertTrue(result.blocks().stream().allMatch(block ->
				block.pageNumber() >= 1 && block.endPageNumber() >= block.pageNumber()));
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

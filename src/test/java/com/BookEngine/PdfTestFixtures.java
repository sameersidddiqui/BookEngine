package com.BookEngine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

public final class PdfTestFixtures {

	private PdfTestFixtures() {
	}

	public static byte[] pdfWithText(String... pageTexts) throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PDDocumentInformation information = new PDDocumentInformation();
			information.setTitle("Example title");
			information.setAuthor("Example author");
			information.setSubject("Example subject");
			information.setCreator("Example creator");
			information.setProducer("Example producer");
			document.setDocumentInformation(information);

			for (String pageText : pageTexts) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(50, 700);
					content.showText(pageText);
					content.endText();
				}
			}

			document.save(output);
			return output.toByteArray();
		}
	}

	public static byte[] structuredPdf() throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			for (int pageNumber = 0; pageNumber < 2; pageNumber++) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					writeLine(content, "Repeated book header", 50, 770, 10, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
					if (pageNumber == 0) {
						writeLine(content, "Chapter One", 50, 710, 18,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
						writeLine(content, "A sentence can cross a hyphen-", 50, 680, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "ated line here.", 50, 665, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "A separate paragraph.", 50, 625, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "- First item", 50, 590, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "- Second item", 50, 575, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "1. First step", 50, 555, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "2. Second step", 50, 540, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "A quoted passage.", 100, 500, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "Another quoted line.", 100, 485, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "A duplicate at the same location.", 50, 450, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "A duplicate at the same location.", 50, 450, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
					}
					else {
						writeLine(content, "Left column first.", 50, 700, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "Left column second.", 50, 685, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "Right column first.", 330, 700, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeLine(content, "Right column second.", 330, 685, 12,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
						writeTableRow(content, 50, 630, "Name", "Count", "Status");
						writeTableRow(content, 50, 612, "Books", "12", "Ready");
						writeLine(content, "for (int i = 0; i < 2; i++)", 70, 550, 11,
								new PDType1Font(Standard14Fonts.FontName.COURIER));
						writeLine(content, "  process(i);", 70, 535, 11,
								new PDType1Font(Standard14Fonts.FontName.COURIER));
						writeLine(content, "1. A small-print note.", 50, 55, 8,
								new PDType1Font(Standard14Fonts.FontName.HELVETICA));
					}
					writeLine(content, "Repeated book footer", 50, 20, 10,
							new PDType1Font(Standard14Fonts.FontName.HELVETICA));
				}
			}
			document.save(output);
			return output.toByteArray();
		}
	}

	private static void writeTableRow(
			PDPageContentStream content, float x, float y, String first, String second, String third) throws IOException {
		content.beginText();
		content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
		content.newLineAtOffset(x, y);
		content.showText(first);
		content.newLineAtOffset(150, 0);
		content.showText(second);
		content.newLineAtOffset(150, 0);
		content.showText(third);
		content.endText();
	}

	private static void writeLine(PDPageContentStream content, String text, float x, float y, float fontSize, PDFont font)
			throws IOException {
		content.beginText();
		content.setFont(font, fontSize);
		content.newLineAtOffset(x, y);
		content.showText(text);
		content.endText();
	}

	public static byte[] emptyPdf() throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.save(output);
			return output.toByteArray();
		}
	}

	public static byte[] encryptedPdf() throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			StandardProtectionPolicy policy = new StandardProtectionPolicy(
					"owner-password", "user-password", new AccessPermission());
			policy.setEncryptionKeyLength(128);
			document.protect(policy);
			document.save(output);
			return output.toByteArray();
		}
	}
}

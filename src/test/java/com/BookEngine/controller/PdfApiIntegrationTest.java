package com.BookEngine.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.BookEngine.PdfTestFixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.upload.max-file-size=2KB")
@AutoConfigureMockMvc
class PdfApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void returnsHealthStatus() throws Exception {
		this.mockMvc.perform(get("/api/v1/pdfs/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void extractsUploadedPdfAndReturnsJsonResponse() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
				"file", "sample.pdf", "application/pdf",
				PdfTestFixtures.pdfWithText("First API paragraph.", "Second API paragraph."));

		MvcResult result = this.mockMvc.perform(multipart("/api/v1/pdfs/extract").file(file))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fileName").value("sample.pdf"))
				.andExpect(jsonPath("$.pageCount").value(2))
				.andExpect(jsonPath("$.metadata.title").value("Example title"))
				.andExpect(jsonPath("$.metadata.author").value("Example author"))
				.andExpect(jsonPath("$.blocks[0].type").value("p"))
				.andExpect(jsonPath("$.text").value(Matchers.containsString("First API paragraph.")))
				.andExpect(jsonPath("$.text").value(Matchers.containsString("Second API paragraph.")))
				.andReturn();
		String text = result.getResponse().getContentAsString();
		assertTrue(text.indexOf("First API paragraph.") < text.indexOf("Second API paragraph."));
	}

	@Test
	void returnsNullForUnavailableMetadataAndOnlyTheBaseFilename() throws Exception {
		MockMultipartFile file = new MockMultipartFile(
				"file", "../../folder/empty.pdf", "application/pdf", PdfTestFixtures.emptyPdf());

		this.mockMvc.perform(multipart("/api/v1/pdfs/extract").file(file))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fileName").value("empty.pdf"))
				.andExpect(jsonPath("$.pageCount").value(0))
				.andExpect(jsonPath("$.metadata.title").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.metadata.author").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.metadata.subject").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.metadata.creator").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.metadata.producer").value(Matchers.nullValue()));
	}

	@Test
	void rejectsMissingAndEmptyUploads() throws Exception {
		this.mockMvc.perform(multipart("/api/v1/pdfs/extract"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_pdf"));

		MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
		this.mockMvc.perform(multipart("/api/v1/pdfs/extract").file(emptyFile))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_pdf"));
	}

	@Test
	void rejectsMalformedAndOversizedUploads() throws Exception {
		MockMultipartFile malformed = new MockMultipartFile(
				"file", "broken.pdf", "application/pdf", "not a PDF".getBytes());
		this.mockMvc.perform(multipart("/api/v1/pdfs/extract").file(malformed))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_pdf"));

		MockMultipartFile oversized = new MockMultipartFile(
				"file", "large.pdf", "application/pdf", new byte[2049]);
		this.mockMvc.perform(multipart("/api/v1/pdfs/extract").file(oversized))
				.andExpect(status().is(413))
				.andExpect(jsonPath("$.error").value("file_too_large"));
	}
}

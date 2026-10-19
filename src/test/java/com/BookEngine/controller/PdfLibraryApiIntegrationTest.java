package com.BookEngine.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.BookEngine.PdfTestFixtures;
import com.BookEngine.service.PdfLibraryStorageService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

@SpringBootTest(properties = "app.upload.max-file-size=2KB")
@AutoConfigureMockMvc
class PdfLibraryApiIntegrationTest {

	private static final Pattern DOCUMENT_ID = Pattern.compile("\"documentId\":\"([^\"]+)\"");

	@TempDir
	static Path storageDirectory;

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void configureStorage(DynamicPropertyRegistry registry) {
		registry.add("app.pdf.storage-dir", () -> storageDirectory.toString());
	}

	@Test
	void savesDeduplicatesResumesRenamesAndDeletesLibraryDocuments() throws Exception {
		Cookie session = initializeSession();
		byte[] pdf = PdfTestFixtures.pdfWithText("First saved paragraph.", "Second saved paragraph.");

		MockMultipartFileUpload first = upload(session, "first.pdf", pdf);
		MvcResult uploaded = this.mockMvc.perform(first.request())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fileName").value("first.pdf"))
				.andExpect(jsonPath("$.lastReadPage").value(1))
				.andReturn();
		String id = documentId(uploaded);
		assertTrue(java.nio.file.Files.exists(storageDirectory.resolve(id + ".pdf")));

		MockMultipartFileUpload duplicate = upload(session, "renamed-source.pdf", pdf);
		this.mockMvc.perform(duplicate.request())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.documentId").value(id))
				.andExpect(jsonPath("$.fileName").value("first.pdf"));

		this.mockMvc.perform(get("/api/v1/pdfs/library").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		this.mockMvc.perform(put("/api/v1/pdfs/library/{id}/progress", id)
						.cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"page\":2,\"progress\":50}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastReadPage").value(2))
				.andExpect(jsonPath("$.lastReadProgress").value(50));
		String ownerKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(session.getValue().getBytes(StandardCharsets.UTF_8)));
		PdfLibraryStorageService restartedStorage = new PdfLibraryStorageService(storageDirectory);
		assertEquals(50, restartedStorage.list(ownerKey).get(0).lastReadProgress());

		this.mockMvc.perform(get("/api/v1/pdfs/library/{id}", id).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fileName").value("first.pdf"))
				.andExpect(jsonPath("$.lastReadPage").value(2))
				.andExpect(jsonPath("$.lastReadProgress").value(50))
				.andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("First saved paragraph.")));

		this.mockMvc.perform(put("/api/v1/pdfs/library/{id}", id)
						.cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fileName\":\"Renamed book\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fileName").value("Renamed book.pdf"));

		Cookie anotherBrowser = initializeSession();
		assertNotEquals(session.getValue(), anotherBrowser.getValue());
		this.mockMvc.perform(get("/api/v1/pdfs/library").cookie(anotherBrowser))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		this.mockMvc.perform(get("/api/v1/pdfs/library/{id}", id).cookie(anotherBrowser))
				.andExpect(status().isNotFound());

		this.mockMvc.perform(delete("/api/v1/pdfs/library/{id}", id).cookie(session))
				.andExpect(status().isNoContent());
		assertTrue(!java.nio.file.Files.exists(storageDirectory.resolve(id + ".pdf")));
		this.mockMvc.perform(get("/api/v1/pdfs/library").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void reportsMissingStoredFilesAndRejectsInvalidProgress() throws Exception {
		Cookie session = initializeSession();
		MockMultipartFileUpload file = upload(session, "missing.pdf",
				PdfTestFixtures.pdfWithText("Saved paragraph."));
		String id = documentId(this.mockMvc.perform(file.request()).andExpect(status().isOk()).andReturn());

		this.mockMvc.perform(put("/api/v1/pdfs/library/{id}/progress", id)
						.cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"page\":99,\"progress\":50}"))
				.andExpect(status().isBadRequest());

		java.nio.file.Files.delete(storageDirectory.resolve(id + ".pdf"));
		this.mockMvc.perform(get("/api/v1/pdfs/library").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].available").value(false));
		this.mockMvc.perform(get("/api/v1/pdfs/library/{id}", id).cookie(session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("document_not_found"));
	}

	private Cookie initializeSession() throws Exception {
		MvcResult result = this.mockMvc.perform(post("/api/v1/pdfs/library/session"))
				.andExpect(status().isNoContent())
				.andReturn();
		String setCookie = result.getResponse().getHeader("Set-Cookie");
		assertTrue(setCookie.contains("HttpOnly"));
		assertTrue(setCookie.contains("SameSite=Strict"));
		return result.getResponse().getCookie("iqra_library");
	}

	private MockMultipartFileUpload upload(Cookie session, String name, byte[] content) {
		return new MockMultipartFileUpload(multipart("/api/v1/pdfs/library")
				.file(new MockMultipartFile("file", name, "application/pdf", content))
				.cookie(session));
	}

	private String documentId(MvcResult result) throws Exception {
		Matcher matcher = DOCUMENT_ID.matcher(result.getResponse().getContentAsString());
		assertTrue(matcher.find());
		return matcher.group(1);
	}

	private record MockMultipartFileUpload(MockMultipartHttpServletRequestBuilder request) {
	}
}

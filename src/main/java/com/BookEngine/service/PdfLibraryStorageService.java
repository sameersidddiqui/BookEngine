package com.BookEngine.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.BookEngine.dto.PdfLibraryItemResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PdfLibraryStorageService {

	private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private final Path storageDirectory;
	private final Path catalogPath;
	private List<StoredDocument> documents;

	public PdfLibraryStorageService(
			@Value("${app.pdf.storage-dir:./data/pdfs}") Path storageDirectory) {
		this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
		this.catalogPath = this.storageDirectory.resolve("library.tsv");
		try {
			Files.createDirectories(this.storageDirectory);
			setPermissions(this.storageDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
			this.documents = loadCatalog();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to initialize the persistent PDF library.", exception);
		}
	}

	public synchronized StoredDocument save(
			String ownerKey, String fileName, byte[] content, int pageCount) {
		String checksum = checksum(content);
		StoredDocument existing = this.documents.stream()
				.filter(document -> document.ownerKey().equals(ownerKey) && document.sha256().equals(checksum)
						&& Files.isRegularFile(pdfPath(document)))
				.findFirst()
				.orElse(null);
		if (existing != null) {
			return existing;
		}

		String id = UUID.randomUUID().toString();
		Instant now = Instant.now();
		StoredDocument document = new StoredDocument(
				id, ownerKey, fileName, id + ".pdf", checksum, content.length, pageCount,
				1, 0, null, now, now);
		Path temporaryFile = null;
		try {
			temporaryFile = Files.createTempFile(this.storageDirectory, ".upload-", ".tmp");
			setPermissions(temporaryFile, PRIVATE_FILE_PERMISSIONS);
			Files.write(temporaryFile, content);
			moveAtomically(temporaryFile, pdfPath(document));
			List<StoredDocument> updated = this.documents.stream()
					.filter(saved -> !saved.ownerKey().equals(ownerKey) || !saved.sha256().equals(checksum)
							|| Files.isRegularFile(pdfPath(saved), LinkOption.NOFOLLOW_LINKS))
					.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
			updated.add(document);
			persistCatalog(updated);
			this.documents = updated;
			return document;
		}
		catch (IOException exception) {
			if (temporaryFile != null) {
				deleteIfExists(temporaryFile);
			}
			deleteIfExists(pdfPath(document));
			throw new IllegalStateException("Unable to store the uploaded PDF.", exception);
		}
	}

	public synchronized List<PdfLibraryItemResponse> list(String ownerKey) {
		return this.documents.stream()
				.filter(document -> document.ownerKey().equals(ownerKey))
				.sorted(Comparator.comparing(StoredDocument::updatedAt).reversed())
				.map(document -> item(document, Files.isRegularFile(pdfPath(document), LinkOption.NOFOLLOW_LINKS)))
				.toList();
	}

	public synchronized StoredDocument find(String ownerKey, String id) {
		return this.documents.stream()
				.filter(document -> document.ownerKey().equals(ownerKey) && document.id().equals(id))
				.findFirst()
				.orElseThrow(() -> new PdfLibraryDocumentNotFoundException());
	}

	public synchronized byte[] read(StoredDocument document) {
		Path file = pdfPath(document);
		try {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw new PdfLibraryDocumentNotFoundException();
			}
			return Files.readAllBytes(file);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to read the saved PDF.", exception);
		}
	}

	public synchronized StoredDocument rename(String ownerKey, String id, String fileName) {
		StoredDocument current = find(ownerKey, id);
		StoredDocument updated = new StoredDocument(
				current.id(), current.ownerKey(), fileName, current.storageFile(), current.sha256(),
				current.fileSize(), current.pageCount(), current.lastReadPage(), current.lastReadProgress(),
				current.lastReadAt(), current.createdAt(), Instant.now());
		replace(updated);
		return updated;
	}

	public synchronized StoredDocument updateProgress(
			String ownerKey, String id, int page, int progress) {
		StoredDocument current = find(ownerKey, id);
		StoredDocument updated = new StoredDocument(
				current.id(), current.ownerKey(), current.fileName(), current.storageFile(), current.sha256(),
				current.fileSize(), current.pageCount(), page, progress, Instant.now(),
				current.createdAt(), Instant.now());
		replace(updated);
		return updated;
	}

	public synchronized void delete(String ownerKey, String id) {
		StoredDocument current = find(ownerKey, id);
		List<StoredDocument> updated = this.documents.stream()
				.filter(document -> !document.id().equals(current.id()))
				.toList();
		persistCatalog(updated);
		this.documents = new ArrayList<>(updated);
		deleteIfExists(pdfPath(current));
	}

	private void replace(StoredDocument updatedDocument) {
		List<StoredDocument> updated = this.documents.stream()
				.map(document -> document.id().equals(updatedDocument.id()) ? updatedDocument : document)
				.toList();
		persistCatalog(updated);
		this.documents = new ArrayList<>(updated);
	}

	private List<StoredDocument> loadCatalog() throws IOException {
		if (!Files.exists(this.catalogPath)) {
			return new ArrayList<>();
		}
		List<StoredDocument> loaded = new ArrayList<>();
		for (String line : Files.readAllLines(this.catalogPath, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			String[] fields = line.split("\t", -1);
			if (fields.length != 12) {
				throw new IOException("The PDF library catalog contains an invalid record.");
			}
			try {
				StoredDocument document = new StoredDocument(
						decode(fields[0]), decode(fields[1]), decode(fields[2]), decode(fields[3]), decode(fields[4]),
						Long.parseLong(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
						Integer.parseInt(fields[8]), parseInstant(fields[9]), parseInstant(fields[10]),
						parseInstant(fields[11]));
				if (!document.id().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
						|| !document.storageFile().equals(document.id() + ".pdf")) {
					throw new IOException("The PDF library catalog contains an invalid record.");
				}
				loaded.add(document);
			}
			catch (IllegalArgumentException exception) {
				throw new IOException("The PDF library catalog contains an invalid record.", exception);
			}
		}
		return loaded;
	}

	private void persistCatalog(List<StoredDocument> updated) {
		Path temporaryFile = null;
		try {
			temporaryFile = Files.createTempFile(this.storageDirectory, ".catalog-", ".tmp");
			setPermissions(temporaryFile, PRIVATE_FILE_PERMISSIONS);
			String serialized = updated.stream().map(document -> String.join("\t",
					encode(document.id()), encode(document.ownerKey()), encode(document.fileName()),
					encode(document.storageFile()), encode(document.sha256()), Long.toString(document.fileSize()),
					Integer.toString(document.pageCount()), Integer.toString(document.lastReadPage()),
					Integer.toString(document.lastReadProgress()), formatInstant(document.lastReadAt()),
					formatInstant(document.createdAt()), formatInstant(document.updatedAt())))
					.reduce((left, right) -> left + "\n" + right).orElse("");
			Files.writeString(temporaryFile, serialized, StandardCharsets.UTF_8);
			moveAtomically(temporaryFile, this.catalogPath);
		}
		catch (IOException exception) {
			if (temporaryFile != null) {
				deleteIfExists(temporaryFile);
			}
			throw new IllegalStateException("Unable to update the persistent PDF library catalog.", exception);
		}
	}

	private String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private String decode(String value) {
		return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
	}

	private String formatInstant(Instant value) {
		return value == null ? "" : value.toString();
	}

	private Instant parseInstant(String value) {
		return value.isEmpty() ? null : Instant.parse(value);
	}

	private PdfLibraryItemResponse item(StoredDocument document, boolean available) {
		return new PdfLibraryItemResponse(
				document.id(), document.fileName(), document.fileSize(), document.pageCount(),
				document.lastReadPage(), document.lastReadProgress(), document.lastReadAt(),
				document.updatedAt(), available);
	}

	private Path pdfPath(StoredDocument document) {
		return this.storageDirectory.resolve(document.id() + ".pdf");
	}

	private String checksum(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private void moveAtomically(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target);
		}
	}

	private void deleteIfExists(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to clean up a temporary PDF library file.", exception);
		}
	}

	private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (UnsupportedOperationException ignored) {
			// The platform does not support POSIX permissions; storage remains outside the web root.
		}
	}

	public record StoredDocument(
			String id,
			String ownerKey,
			String fileName,
			String storageFile,
			String sha256,
			long fileSize,
			int pageCount,
			int lastReadPage,
			int lastReadProgress,
			Instant lastReadAt,
			Instant createdAt,
			Instant updatedAt) {
	}
}

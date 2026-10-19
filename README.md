# BookEngine

Iqra is a standalone Spring Boot application that extracts text and metadata
from PDF files. PDF processing is performed locally with Apache PDFBox; the
application does not send documents to external services. Saved PDFs and their
library metadata are stored on the configured server filesystem; no database
or cloud storage is required.

Extracted text is organized into semantic reader blocks (`p`, `h2`, `ul`,
`ol`, `blockquote`, `table`, `code`, and `footnote`) using PDF text positions,
columns, font sizes, spacing, and repeated page-edge text. Heuristics retain
ambiguous content rather than aggressively rewriting it. This is deterministic
rule-based processing; no AI or OCR service is used. The existing `text`
response field remains available for plain-text copy and download.

## Technology and prerequisites

- Java 21
- Gradle (use the included Gradle wrapper)
- Spring Boot 4.1.1
- Apache PDFBox 3.0.7

## Run the application

Start the server with:

```sh
./gradlew bootRun
```

The application listens on `http://localhost:8080` by default. Check that it
is running:

```sh
curl http://localhost:8080/api/v1/pdfs/health
```

Open `http://localhost:8080/` in a browser to upload a PDF, inspect the
extracted text and metadata, copy or download the text, or read the extracted
text in the built-in reader. Uploaded PDFs are saved in a private library
scoped to that browser by an HttpOnly cookie. The library and reading position
survive page reloads, browser restarts, and application restarts as long as the
configured storage directory is retained.

The library supports content-hash deduplication, renaming, deletion, and
automatic reading-position saves. Reading position is represented by the
reader's overall progress and an estimated page number because extracted text
is reflowed for reading rather than rendered as fixed PDF pages.

## Build and run with Docker

Build the production image from the repository root:

```sh
docker build -t bookengine .
```

Run it locally on port 8080:

```sh
docker run --rm -p 8080:8080 -e PORT=8080 bookengine
```

The runtime image uses Java 21 and a non-root user. It listens on the `PORT`
environment variable (default `10000` in Docker and `8080` when run directly),
as expected by Render. `.dockerignore` keeps local build outputs, environment
files, and secret/credential files out of the build context. Configure upload
limits with `APP_UPLOAD_MAX_FILE_SIZE` (default `10MB`) and
`APP_UPLOAD_MAX_REQUEST_SIZE` (default `11MB`). Supply deployment-specific
settings through environment variables rather than storing credentials in the
repository.

On Render, create a Blueprint from the repository's `render.yaml`, or create a
Docker web service from the `main` branch using the repository root as its
Docker build context. The Blueprint sets the health check to
`/api/v1/pdfs/health`; Render supplies `PORT` to the container at runtime.
The default storage directory is `./data/pdfs` and can be overridden with
`APP_PDF_STORAGE_DIR`. The Docker image uses `/app/data/pdfs` by default and
creates that directory with write access for its non-root application user.
Filesystems on many hosted web services are ephemeral; attach a persistent
disk and set this variable to its mount path (for example `/var/data/pdfs`) to
retain the library across deployments and instance replacement. The browser
cookie scopes a library to one browser, not to a user account; the application
does not currently provide account sign-in or cross-device synchronization.

## Extract a PDF

Send a PDF as multipart form data using the field name `file`:

```sh
curl -X POST \
  -F 'file=@sample.pdf' \
  http://localhost:8080/api/v1/pdfs/extract
```

A successful response includes the uploaded file name, page count, available metadata, normalized extracted text, and structured blocks:

```json
{
  "fileName": "sample.pdf",
  "pageCount": 2,
  "metadata": {
    "title": "Example document",
    "author": "Example author",
    "subject": null,
    "creator": null,
    "producer": "Apache PDFBox"
  },
  "text": "Chapter One\n\nExtracted document text...",
  "blocks": [
    { "type": "h2", "text": "Chapter One" },
    { "type": "p", "text": "Extracted document text..." }
  ]
}
```

Unavailable metadata is returned as `null`. PDFBox, rather than the filename
or declared content type, determines whether the uploaded file is a valid PDF.
Returned filenames contain only the base name, not client-supplied directories.

## Upload limits and errors

The maximum PDF size defaults to 10 MB and can be changed with the
`app.upload.max-file-size` property. Spring's multipart request-size limit
defaults to 11 MB to allow for multipart framing; override it with
`app.upload.max-request-size` if needed.

Errors use a JSON object with `error` and `message` fields. Missing, empty,
malformed, and password-protected PDFs return HTTP 400. Oversized uploads
return HTTP 413. Unexpected processing failures return HTTP 500 with a generic
message; server details are logged and are not included in the response.

## PDF library API

The browser initializes a persistent, browser-scoped library session with
`POST /api/v1/pdfs/library/session`. The returned HttpOnly, SameSite cookie is
required for library requests. The API supports:

- `POST /api/v1/pdfs/library` — validate, extract, and save an uploaded PDF;
  identical content within the same browser library returns its existing ID.
- `GET /api/v1/pdfs/library` — list saved documents and their last-read page
  and progress.
- `GET /api/v1/pdfs/library/{id}` — re-extract and open a saved PDF.
- `PUT /api/v1/pdfs/library/{id}` — rename using `{"fileName":"New name"}`.
- `PUT /api/v1/pdfs/library/{id}/progress` — save `{"page":2,"progress":35}`.
- `DELETE /api/v1/pdfs/library/{id}` — remove a saved PDF.

PDF files and the atomic library catalog are stored outside the static web
root, with owner-only POSIX permissions where supported. Configure the storage
directory so it is accessible only to the application process and back it up
according to your deployment's data-retention requirements.

Scanned PDFs containing only images are not OCR-processed. Text extraction
returns the text layer available in a document; OCR support is not included.

## Run tests

Run all unit and API integration tests with:

```sh
./gradlew test
```

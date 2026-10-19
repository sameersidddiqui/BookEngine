# BookEngine

Iqra is a standalone Spring Boot application that extracts text and metadata
from PDF files. PDF processing is performed locally with Apache PDFBox; the
application does not send documents to external services. It runs without a
database or cloud storage.

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
text in the built-in reader.

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

Scanned PDFs containing only images are not OCR-processed. Text extraction
returns the text layer available in a document; OCR support is not included.

## Run tests

Run all unit and API integration tests with:

```sh
./gradlew test
```

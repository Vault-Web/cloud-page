# Cloud Page Backend

## Overview

Cloud Page is the backend service for file and folder management in the Vault Web ecosystem. It provides the APIs used to browse, create, update, delete, search, share, and download files securely.

This repository contains only the backend implementation. The Cloud user interface is available in the `vault-web` repository.

---

## Responsibilities

The backend is responsible for:

- Managing files and folders
- Handling uploads and downloads
- Searching files with metadata filters
- Creating and managing Secure Send links
- Supporting file and folder sharing
- Enforcing authentication and access control
- Connecting with the shared Vault Web database

---

## Running Standalone

Cloud Page can be run independently for local development.

Before starting the application, make sure the required Vault Web services, including PostgreSQL, are already running.

Start the backend with:

```bash
./mvnw spring-boot:run
```

The application starts on port `8090` by default.

---

## Running Tests

Run all backend tests with:

```bash
./mvnw test
```

---

## Package Structure

The project is organized into the following packages:

| Package | Purpose |
|---------|---------|
| `controller` | Handles incoming API requests |
| `service` | Contains the business logic |
| `model` | Defines the application's data models |
| `dto` | Stores data transfer objects used by the API |
| `ratelimit` | Controls request rate limiting |
| `scan` | Handles file scanning functionality |

---

## ClamAV

Cloud Page supports ClamAV for scanning uploaded files for viruses.

Virus scanning is disabled by default, so the application can run locally without a ClamAV service.

To enable virus scanning, set:

```properties
cloudpage.virus-scan.enabled=true
```

Then configure the ClamAV host and port in the application configuration.

Refer to the project configuration for any settings related to enabling or disabling file scanning.

## Search

`GET /api/folders/search` performs a fuzzy (Jaro-Winkler) name match and accepts optional metadata
filters and sort controls:

```
GET /api/folders/search?folderPath=/&query=report&type=file&minSize=1024&sortBy=size
```

| Param | Description |
|-------|-------------|
| `type` | `file` or `folder` |
| `mimeType` | MIME-type prefix, e.g. `image` matches `image/png` |
| `minSize` / `maxSize` | size bounds in bytes |
| `modifiedAfter` / `modifiedBefore` | last-modified bounds (epoch millis) |
| `sortBy` | `relevance` (default), `name`, `size`, or `lastModified` |
| `ascending` | sort direction; defaults to `false` (best / largest / newest first) |

---

## Inline File Viewing

`GET /api/files/view?path=<user-relative-path>` serves a file for display in the browser. The
response keeps the file's detected MIME type, uses `Content-Disposition: inline`, and supports
HTTP byte-range requests for seeking in video, audio, and PDF files. Unknown file types use
`application/octet-stream`, allowing the client to show a download fallback.

The endpoint uses the same authenticated user root and path validation as the other file APIs.
Viewer actions can reuse the existing endpoints:

| Action | Endpoint |
|-------|----------|
| Rename or move | `PATCH /api/files/move?filePath=<path>&newPath=<path>` |
| Delete (move to trash) | `DELETE /api/files?filePath=<path>` |
| Download | `GET /api/files/download?path=<path>` |
| Next/previous source list | `GET /api/folders/content?path=<folder>&page=<page>&size=<size>` |

All requests require the existing bearer-token authentication. A frontend using native
`<video>`, `<audio>`, `<img>`, or embedded PDF elements should expose the endpoint through its
authenticated same-origin backend/proxy, because those elements cannot attach an arbitrary
`Authorization` request header.

---

## Folder Archive Download

`GET /api/folders/download?path=<user-relative-folder>` streams the selected folder as a ZIP
archive. The archive keeps nested and empty directories, excludes `.trash`, and does not follow
symbolic links. Omitting `path` downloads the authenticated user's root folder.

The response uses `Content-Type: application/zip` and an attachment filename based on the selected
folder. Requests use the existing download rate limit and the same authenticated user-root path
validation as other file and folder APIs.

---

## Secure Send

Secure Send exposes one file through an opaque, expiring external URL. It is separate from
user-to-user sharing: the recipient does not need an account and cannot browse the owner's files.

All management endpoints require the normal JWT:

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/secure-sends` | Create a link for one file |
| `GET` | `/api/secure-sends` | List the current user's links |
| `DELETE` | `/api/secure-sends/{id}` | Revoke a link immediately |

Create request:

```json
{
  "filePath": "documents/report.pdf",
  "expiresAt": "2026-07-19T10:00:00Z",
  "password": "optional password"
}
```

The response includes a `url` suitable for the file viewer or chat UI. The raw token is returned
only as part of this URL and is not stored by the service. Consequently, the listing endpoint does
not return reusable URLs; if the creation response is lost, revoke the entry and create a new link.

The recipient downloads through the public endpoint:

```http
GET /api/public/secure-sends/{token}
X-Secure-Send-Password: optional password
```

Passwords are sent in a header so they do not appear in URLs or browser history. Unknown, expired,
revoked, deleted, or moved targets return `404`; an incorrect or missing required password returns
`401`. A link never accepts a file path and therefore cannot be used to list or select another file.

Secure Send expiry, cleanup, and rate limits are configurable with
`cloudpage.secure-send.*` and `cloudpage.rate-limit.per-client.secure-send-*` properties. Expiry
blocks access immediately, but the database record remains for the configured retention period
before scheduled cleanup removes it.

---

## User-to-user Sharing

Registered users can grant another registered user access to one file or folder without exposing
the rest of their storage. This authenticated flow is separate from Secure Send. Shares support
`VIEW`, `DOWNLOAD`, and `EDIT` permissions; permissions are checked again on every shared
operation.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/shares` | Share an owned file or folder |
| `GET` | `/api/shares` | List shares created by the current user, including revoked shares |
| `GET` | `/api/shares/shared-with-me` | List active shares received by the current user |
| `DELETE` | `/api/shares/{id}` | Revoke an owned share immediately |
| `GET` | `/api/shares/{id}/content?path=<relative-path>` | List a shared folder or nested folder; requires `VIEW` |
| `GET` | `/api/shares/{id}/view?path=<relative-path>` | View a shared file or nested file; requires `VIEW` |
| `GET` | `/api/shares/{id}/download?path=<relative-path>` | Download a shared file or nested file; requires `DOWNLOAD` |
| `GET` | `/api/shares/{id}/download-folder?path=<relative-path>` | Download a shared folder or nested folder as ZIP; requires `DOWNLOAD` |
| `PUT` | `/api/shares/{id}/edit?path=<relative-path>` | Replace a shared file or nested file using multipart field `file`; requires `EDIT` |

Create request:

```json
{
  "path": "projects/website",
  "recipientUsername": "bob",
  "permissions": ["VIEW", "DOWNLOAD", "EDIT"]
}
```

The resource type is inferred from the owned path. A recipient uses the returned share ID and, for
a folder share, may supply only paths relative to that shared folder. Omitting `path` addresses the
shared file or folder itself. Absolute paths, parent traversal outside the shared folder, symbolic
link escapes, and `.trash` paths are rejected. Moving or deleting the original resource makes the
share unavailable; revocation removes recipient access immediately. `EDIT` replaces the contents
of an existing shared file and cannot create files or change anything outside the shared boundary.
It also enforces the owner's storage quota. Creating the same active share again reuses that share
and updates its permissions instead of adding a duplicate. Shared browsing, downloads, and edits
use the existing listing, download, and upload rate-limit budgets respectively.

---

## Local Development

Cloud Page relies on the **Vault Web Docker setup** for PostgreSQL and pgAdmin. Make sure you have the **Vault Web environment running** before starting Cloud Page.

---

### 1. Clone the Repository

```bash
git clone https://github.com/Vault-Web/cloud-page.git
cd cloud-page
````

---

### 2. Configure `.env`

Create a `.env` file in the root directory with:

```env
# JWT config
MASTER_KEY=your_master_key_here
````

> 📝 Make sure **PostgreSQL from the Vault Web Docker setup is running** before starting Cloud Page.
> Run `docker compose up -d` in the Vault Web repository if not already running.
> The database credentials are inherited from the Vault Web `.env` setup.
> Do **not** use production secrets during local development.

---

### 3. Start the Backend

The backend runs on port `8090` (can be changed in `application.properties`).
Make sure the Vault Web Docker stack is already running (PostgreSQL & pgAdmin).

```bash
./mvnw spring-boot:run
```

Then visit:

* API Base: [http://localhost:8090](http://localhost:8090)
* Swagger UI: [http://localhost:8090/swagger-ui.html](http://localhost:8081/swagger-ui.html)

---


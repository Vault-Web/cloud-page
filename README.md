# Cloud Page

Cloud Page is the backend service for file and folder management in the Vault Web ecosystem. It provides APIs for browsing, creating, updating, deleting, searching, sharing, and downloading files securely.

> **Note**
>
> This repository contains only the backend service. The Cloud user interface is part of the `vault-web` repository under `frontend/src/app/pages/cloud`.

---

## Features

- File and folder management
- Search with metadata filters
- Inline file viewing
- Folder archive downloads
- Secure Send links
- User to user sharing

For detailed feature documentation, see [`backend/README.md`](backend/README.md).

---

## Quick Start

1. Clone this repository.
2. Start the required Vault Web services.
3. Configure the required environment variables.
4. Run the backend.

For detailed setup instructions, see [`backend/README.md`](backend/README.md#local-development).

---

## Dependencies

Cloud Page works with the following components in the Vault Web ecosystem.

### Vault Web

Cloud Page uses Vault Web for authentication and shared infrastructure. The Cloud user interface is located in the `vault-web` repository under `frontend/src/app/pages/cloud`.

### Deploy

The deployment repository contains the production configuration and user root folder mapping used by Cloud Page.

## ClamAV

Cloud Page supports ClamAV for scanning uploaded files for viruses.

Virus scanning is disabled by default, so the application can run locally without a ClamAV service.

To enable scanning, set:

```properties
cloudpage.virus-scan.enabled=true
```

Then configure the ClamAV host and port in the application configuration.

This allows developers to work on the project without installing ClamAV while still supporting virus scanning when it is available.

---

## Project Structure

See [DIRECTORY.md](DIRECTORY.md) for an overview of the project structure.

---

## Questions

For questions or issues, please open an issue in this repository.

For integration or usage questions related to Vault Web, refer to the Vault Web documentation.
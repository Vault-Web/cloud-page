# Cloud Page

**Cloud Page** is a backend service in the Vault Web ecosystem for **user-specific file and folder management**.  
It provides APIs for accessing, creating, editing, and deleting files securely, similar to a traditional file explorer.  

This service is designed to integrate seamlessly with **Vault Web**, sharing its **PostgreSQL database** and **pgAdmin setup**.

---

## Features

- 🔹 **File Explorer-like API** for user files  
- 🔹 **CRUD operations** on files and folders  
- 🔹 **Secure access via JWT tokens** using Vault Web's master key  

---

## Project Structure

- Backend implemented in **Spring Boot**  
- Uses **PostgreSQL** from the Vault Web repository for storage  
- See [**DIRECTORY.md**](https://github.com/Vault-Web/cloud-page/blob/main/DIRECTORY.md) for full project structure  

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

## Notes

* This service **depends on Vault Web** for database and authentication.
* JWT tokens must use the **same master key** as Vault Web.

---

## 📫 Questions?

For any issues, feel free to open an issue in this repository.
Integration or usage questions related to Vault Web should reference the main Vault Web documentation.
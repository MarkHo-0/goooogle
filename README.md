# Goooogle

## 1. Project Overview
Goooogle is a university course project with the final goal of building a complete web search engine.
Phase 1 has been completed, including web crawling, indexing, link processing, and data export.
Phase 2 focuses on implementing full search and keyword retrieval functionalities.

### Key Third-party Libraries
- Spring Boot (application framework for MVC routing)
- SQLite JDBC (local SQLite database)
- Jsoup (HTML downloader and parser used by the spider)
- Thymeleaf (server-side template engine for rendering web pages)

## 2. Installation and Run

1. Install JDK 25 (as configured in [pom.xml](pom.xml)).
2. Clone the repository.
3. Open the project root directory.
4. Run the application:
```bash
# Windows
./mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```
5. Open your browser and navigate to `localhost:8080`

## 3. Completed Features and User Guides

### 3.1 Spider
1. Switch to `Spider` page from the nav bar.
2. Enter the start URL and max number of pages to crawl.
3. Click `Start Crawling`.
4. Completion should be confirmed from logs by showing `Spider completed in x seconds` in the backend console, as dynamic communication is not yet implemented on the frontend.

### 3.2 Export Indexed Data

1. Click `Export Database to TXT` in the `Spider` page after the completion of indexing 
2. `spider_result.txt` will be generated and downloaded automatically.

## 4. Project Architecture

### 4.1 Layered Structure
- Controller layer:
	[PageController.java](src/main/java/com/hkust/goooogle/PageController.java) handles routes and binds UI with services.
- Service layer:
	- [SpiderService.java](src/main/java/com/hkust/goooogle/services/SpiderService.java): crawl workflow and page persistence
	- [IndexerService.java](src/main/java/com/hkust/goooogle/services/IndexerService.java): token processing and keyword indexing
	- [SearchService.java](src/main/java/com/hkust/goooogle/services/SearchService.java): search logic placeholder
	- [KeywordService.java](src/main/java/com/hkust/goooogle/services/KeywordService.java): keyword query placeholder
- Data/model layer:
	[Page.java](src/main/java/com/hkust/goooogle/models/Page.java) and [ExportedPage.java](src/main/java/com/hkust/goooogle/models/ExportedPage.java)
- View layer:
	Thymeleaf templates in [src/main/resources/templates](src/main/resources/templates)

### 4.2 Spider Workflow
1. Spider fetches HTML pages through Jsoup.
2. Page metadata is stored in `pages` and outbound links are written to `pending_links`.
3. Indexer tokenizes and normalizes text, then stores into `words` and `keywords`.
4. Pending links are resolved into `links`.
5. This process repeats until the maximum number of pages specified has been crawled.

### 4.3 Database Schema
- `pages`
	- `id` (INTEGER, primary key, auto increment)
	- `url` (TEXT, unique)
	- `title` (TEXT)
	- `last_modify_time` (TEXT)
	- `content_size` (INTEGER)
- `words`
	- `id` (INTEGER, primary key, auto increment)
	- `word` (TEXT, unique)
- `keywords`
	- `page_id` (INTEGER, foreign key -> `pages.id`)
	- `word_id` (INTEGER, foreign key -> `words.id`)
	- `body_count` (TINYINT, default 0)
	- `title_count` (TINYINT, default 0)
	- Composite primary key: (`page_id`, `word_id`)
- `links`
	- `parent_page_id` (INTEGER, foreign key -> `pages.id`)
	- `child_page_id` (INTEGER, foreign key -> `pages.id`)
	- Composite primary key: (`parent_page_id`, `child_page_id`)
- `pending_links`
	- `page_id` (INTEGER, foreign key -> `pages.id`)
	- `outbound_link` (TEXT)
	- Composite primary key: (`page_id`, `outbound_link`)

### Notes
- SQLite file `goooogle.db` is created in the project root automatically.
- Database schema is initialized on startup by [DatabaseSchemaInitializer.java](src/main/java/com/hkust/goooogle/DatabaseSchemaInitializer.java).
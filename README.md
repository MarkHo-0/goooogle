# Goooogle

## 1. Project Overview
Goooogle is a university course project with the final goal of building a complete web search engine. It provides a complete local search engine with web crawling, indexing, relevance-based search, keyword browsing, link processing, and exportable crawl results.

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
5. Open your browser and navigate to `localhost:8443`

## 3. Feature Highlights and User Guides

### 3.1 Search
1. Open the `Search` page from the nav bar.
2. Enter one or more keywords in the search box and submit the query.
3. Wrap words in double quotes to require an exact phrase match.
4. Results are ranked by relevance and show the matching pages plus the search time.

### 3.2 Keywords
1. Open the `Keywords` page from the nav bar.
2. Search keyword text with filters, or sort by name/count using the page controls.
3. Adjust the page size and offset to browse more results.
4. Each keyword entry shows the top associated pages.

### 3.3 Spider
1. Switch to `Spider` page from the nav bar.
2. Enter the start URL and max number of pages to crawl.
3. Click `Start Crawling`.
4. Crawling runs in a background thread, and completion is reported in the backend console as `Spider completed in x seconds`.

### 3.4 Export Indexed Data

1. Click `Export Database to TXT` on the `Spider` page after indexing is complete.
2. The browser downloads `spider_result.txt` as a text file.

## 4. Project Architecture

### 4.1 Layered Structure
- Controller layer:
	[PageController.java](src/main/java/com/hkust/goooogle/PageController.java) handles the home, search, spider, keywords, and export routes.
- Service layer:
	- [SpiderService.java](src/main/java/com/hkust/goooogle/services/SpiderService.java): crawl workflow and page persistence
	- [IndexerService.java](src/main/java/com/hkust/goooogle/services/IndexerService.java): token processing, normalization, stemming, and keyword indexing
	- [SearchService.java](src/main/java/com/hkust/goooogle/services/SearchService.java): TF-IDF-style relevance scoring, cosine similarity, and exact-phrase filtering
	- [KeywordService.java](src/main/java/com/hkust/goooogle/services/KeywordService.java): keyword search, sorting, pagination, and page-count lookup
- Data/model layer:
	[Page.java](src/main/java/com/hkust/goooogle/models/Page.java), [Word.java](src/main/java/com/hkust/goooogle/models/Word.java), [ExportedPage.java](src/main/java/com/hkust/goooogle/models/ExportedPage.java), and [Rankable.java](src/main/java/com/hkust/goooogle/models/Rankable.java)
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
	- `max_term_count` (TINYINT)
	- `full_page` (TEXT)
- `words`
	- `id` (INTEGER, primary key, auto increment)
	- `word` (TEXT, unique)
- `keywords`
	- `page_id` (INTEGER, foreign key -> `pages.id`)
	- `word_id` (INTEGER, foreign key -> `words.id`)
	- `body_count` (TINYINT, default 0)
	- `title_count` (TINYINT, default 0)
	- `total_count` (TINYINT, default 0)
- `links`
	- `parent_page_id` (INTEGER, foreign key -> `pages.id`)
	- `child_page_id` (INTEGER, foreign key -> `pages.id`)
- `pending_links`
	- `page_id` (INTEGER, foreign key -> `pages.id`)
	- `outbound_link` (TEXT)

### Notes
- SQLite file `goooogle.db` is created in the project root automatically.
- Database schema is initialized on startup by [DatabaseSchemaInitializer.java](src/main/java/com/hkust/goooogle/DatabaseSchemaInitializer.java).
# Roch - Intelligent Q&A System Based on RAG

## Project Overview

Roch is a Retrieval-Augmented Generation (RAG) system built on the Spring AI framework, supporting features such as
multi-turn conversations, intelligent document import, semantic chunking, and retrieval logging. The project adopts a
modular design, allowing flexible expansion for various document formats and vector database integrations.

## Core Features

### Document Import and Processing

- **Multi-format Support**: Supports importing documents in TXT, Word (.docx), PDF, and Excel (.xlsx/.xls) formats.
- **Intelligent Semantic Chunking**: Text chunking based on semantic similarity, preserving document structure (heading
  hierarchy, code blocks, tables, etc.).
- **Version Management**: Supports rebuilding and deduplication of different chunking strategy versions for the same
  document.
- **MD5 Deduplication Mechanism**: Automatically skips duplicate files to avoid redundant imports.

### Intelligent Q&A

- **Single-turn Conversation**: Simple Q&A without context memory.
- **Multi-turn Conversation**: Context memory mechanism based on conversation ID.
- **Streaming Output**: Supports SSE real-time streaming responses.
- **Knowledge Base Retrieval Enhancement**: Configurable RAG switch, number of returned items, and similarity threshold.

### Retrieval and Logging

- **Vector Retrieval**: Semantic similarity retrieval based on Milvus.
- **Retrieval Logging**: Records hit status, latency, and metadata for each retrieval, supporting memory buffer queries.
- **Citation Tracing**: Returns document fragment summaries to facilitate answer sourcing.

## Technology Stack

| Category              | Technology Selection                  |
|-----------------------|---------------------------------------|
| Development Framework | Spring Boot 3.x + Spring AI           |
| Vector Database       | Milvus                                |
| Document Parsing      | Apache POI (Word/Excel), PDFBox (PDF) |
| Text Processing       | Jsoup (HTML to Markdown), LangChain4j |
| Build Tool            | Maven + Maven Wrapper                 |
| Containerization      | Docker / Docker Compose               |

## Quick Start

### Environment Requirements

- JDK 22+
- Maven 3.8+ (Maven Wrapper is included in the project)
- Milvus Database (Standalone or Docker deployment)

### Local Run

```bash
# Clone the project
git clone https://gitee.com/cwnk256/roch.git
cd roch

# Build using Maven Wrapper
./mvnw clean package -DskipTests

# Start RAG Service
./mvnw spring-boot:run -pl rag
```

The service listens on `http://localhost:8080` by default.

### Docker Deployment

```bash
# Build image
docker build -t roch:latest .

# Or use Docker Compose (requires prior configuration)
docker compose up -d
```

## Configuration Instructions

Main configuration items are located in `rag/src/main/resources/application.properties`:

| Configuration Item                            | Default Value | Description                                     |
|-----------------------------------------------|---------------|-------------------------------------------------|
| `spring.ai.vectorstore.milvus.collectionName` | rag_docs      | Milvus Collection Name                          |
| `spring.ai.vectorstore.milvus.databaseName`   | default       | Database Name                                   |
| `spring.ai.embedding.openai.api-key`          | -             | OpenAI API Key (if using OpenAI Embedding)      |
| `rag.chunking.strategy`                       | legacy-greedy | Chunking Strategy                               |
| `rag.chunking.similarity-threshold`           | 0.7           | Semantic Chunking Similarity Threshold          |
| `rag.chat.memory.max-messages`                | 20            | Max Messages for Multi-turn Conversation Memory |
| `rag.chat.rag.enabled`                        | false         | Whether to enable RAG retrieval by default      |
| `rag.chat.rag.top-k`                          | 4             | Number of Retrieved Items                       |
| `rag.retrieval.log.enabled`                   | true          | Whether to log retrieval logs                   |

> **Note**: Please refer to the `@ConfigurationProperties` class in the source code for complete configuration items and
> default values.

## API Documentation

### Chat Interface

#### Single-turn Chat

```http
POST /api/chat
Content-Type: application/json

{
  "message": "Hello, please introduce yourself."
}
```

#### Multi-turn Q&A

```http
POST /api/chat/multi-turn
Content-Type: application/json

{
  "conversationId": "3f2a9c1e-8b44-4f0a-9d21-6c9b7a1f0e33",  // Optional, auto-created if not provided
  "message": "Then what is its price?",
  "ragEnabled": true,
  "topK": 4,
  "threshold": 0.5
}
```

#### Streaming Multi-turn Q&A

```http
POST /api/chat/multi-turn/stream
Accept: text/event-stream
Content-Type: application/json

{
  "conversationId": "3f2a9c1e-8b44-4f0a-9d21-6c9b7a1f0e33",
  "message": "Explain in detail"
}
```

#### Session Management

| Method | Path                                    | Description           |
|--------|-----------------------------------------|-----------------------|
| POST   | `/api/chat/conversations`               | Create new session    |
| GET    | `/api/chat/conversations/{id}/messages` | Query session history |
| DELETE | `/api/chat/conversations/{id}`          | Clear session         |

### Document Import Interface

| Method | Path                   | Content-Type                       | Description           |
|--------|------------------------|------------------------------------|-----------------------|
| POST   | `/api/documents`       | `multipart/form-data` (.txt)       | Import TXT document   |
| POST   | `/api/documents/word`  | `multipart/form-data` (.docx)      | Import Word document  |
| POST   | `/api/documents/pdf`   | `multipart/form-data` (.pdf)       | Import PDF document   |
| POST   | `/api/documents/excel` | `multipart/form-data` (.xlsx/.xls) | Import Excel document |

Response Example:

```json
{
  "chunkCount": 15,
  "md5": "e10adc3949ba59abbe56e057f20f883e",
  "duplicated": false,
  "message": "Successfully imported document, split into 15 chunks"
}
```

### Retrieval Log Query Interface

```http
GET /api/retrieval-logs?conversationId=xxx&limit=50
GET /api/retrieval-logs/{logId}
```

> **Note**: Retrieval logs are stored in memory buffers; data will be lost after service restart.

### Tool Interface

| Method | Path              | Description                                   |
|--------|-------------------|-----------------------------------------------|
| POST   | `/api/curl`       | Request external JSON API and persist to disk |
| GET    | `/api/curl/files` | List saved JSON files                         |
| POST   | `/api/curl/page`  | Crawl webpage and convert to Markdown         |

## Project Structure

```
roch/
├── Dockerfile                 # Multi-stage build image
├── compose.yaml               # Docker Compose configuration
├── mvnw / .mvn/               # Maven Wrapper
├── rag/                       # Main module
│   ├── src/main/java/com/wuyunbin/rag/
│   │   ├── RagApplication.java           # Startup class
│   │   ├── cleaning/                     # Document cleaning and chunking
│   │   │   ├── CleaningTextSplitter.java
│   │   │   ├── StructuredSegmenter.java  # Structured segmentation (including heading awareness)
│   │   │   ├── SemanticTextSplitter.java # Semantic chunking
│   │   │   └── ...
│   │   ├── config/                       # Spring configuration
│   │   │   ├── ChatConfig.java           # ChatClient, ChatMemory Bean
│   │   │   └── RestConfig.java
│   │   ├── controller/                   # REST Controllers
│   │   │   ├── ChatController.java       # Single-turn chat
│   │   │   ├── MultiTurnChatController.java
│   │   │   ├── DocumentController.java
│   │   │   └── RetrievalLogController.java
│   │   ├── dto/                          # Data Transfer Objects
│   │   ├── retrieval/                    # Vector retrieval and logging
│   │   │   ├── RagRetriever.java
│   │   │   ├── RetrievalLogWriter.java
│   │   │   └── InMemoryRetrievalLogStore.java
│   │   ├── service/                      # Business logic
│   │   │   ├── ChatService.java
│   │   │   ├── MultiTurnChatService.java
│   │   │   ├── DocumentService.java
│   │   │   └── document/                 # Various document parsers
│   │   └── ...
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── logback-spring.xml
│   └── src/test/java/                    # Unit tests
├── plan/                                 # Requirements and Design Documents
│   ├── multi-turn-chat/                  # Multi-turn conversation implementation plan
│   ├── recalled-document/                # Retrieval log feature plan
│   └── semantic-chunking/                # Semantic chunking enhancement plan
└── scripts/
    ├── rag_eval.py                       # RAG evaluation script
    └── test_set_*.json                   # Test sets
```

## Development Guide

### Adding New Document Format Support

1. Create a parser class under the `service/` package and implement document parsing logic.
2. Inject and call it in `DocumentService`.
3. Add corresponding endpoints in `DocumentController`.

### Extending Chunking Strategies

1. Implement the `DocumentTransformer` interface.
2. Register the new strategy in `CleaningTextSplitter`.
3. Expose configuration items via `ChunkingProperties`.

### Running Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=ChunkingBaselineTest
```

## Roadmap

See each phase implementation plan in the `plan/` directory:

- **Plan 01**: Chunking versioning and evaluation baseline
- **Plan 02**: Heading and chapter-aware chunking
- **Plan 03**: Adaptive boundary and window strategies
- **Plan 04**: Parent-child chunking and retrieval extension

## License

This project follows the [Apache License 2.0](./LICENSE) open source agreement.

## Contributors

Thanks to all developers who have provided feedback and contributions to the project. If you have suggestions or issues,
feel free to report them via Gitee Issues.
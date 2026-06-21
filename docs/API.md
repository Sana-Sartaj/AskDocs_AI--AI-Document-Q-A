# API Reference

**Base URL:** `http://localhost:8080/api/v1`
**Interactive docs:** `http://localhost:8080/swagger-ui.html`
**OpenAPI schema:** `http://localhost:8080/api-docs`

All responses are wrapped in `ApiResponse<T>`:
```json
{ "data": { ... }, "success": true }
{ "data": null, "error": "message", "success": false }
```

---

## Authentication

### POST /auth/register

Create a new user account and receive a JWT.

**Request**
```json
{
  "email": "user@example.com",
  "password": "SecureP@ss1",
  "fullName": "Jane Smith"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `email` | string | Yes | Must be unique |
| `password` | string | Yes | |
| `fullName` | string | No | |

**Response 201**
```json
{
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expirationMs": 86400000,
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "fullName": "Jane Smith",
    "role": "USER"
  },
  "success": true
}
```

**Errors**
| Status | Condition |
|---|---|
| 409 | Email already registered |
| 400 | Validation failure |

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"SecureP@ss1","fullName":"Jane Smith"}'
```

---

### POST /auth/login

Authenticate and receive a JWT.

**Request**
```json
{
  "email": "user@example.com",
  "password": "SecureP@ss1"
}
```

**Response 200** — same shape as `/register`

**Errors**
| Status | Condition |
|---|---|
| 401 | Invalid credentials |
| 400 | Validation failure |

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"SecureP@ss1"}' \
  | jq -r '.data.token')
```

---

## Users

### GET /users/me

Returns the current authenticated user's profile.

**Response 200**
```json
{
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "fullName": "Jane Smith",
    "role": "USER"
  },
  "success": true
}
```

```bash
curl http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer $TOKEN"
```

---

## Documents

### POST /documents

Upload a document (PDF, DOCX, TXT, Markdown, CSV; max 50 MB). Processing is asynchronous — the document status moves from `UPLOADED` → `PROCESSING` → `PROCESSED`.

**Request** — multipart/form-data

| Field | Type | Required |
|---|---|---|
| `file` | binary | Yes |
| `title` | string | Yes |

**Response 201**
```json
{
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "title": "Annual Report 2024",
    "originalFilename": "report.pdf",
    "contentType": "application/pdf",
    "fileSizeBytes": 4194304,
    "status": "UPLOADED",
    "chunkCount": 0,
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:00Z"
  },
  "success": true
}
```

**Document status values**

| Status | Meaning |
|---|---|
| `UPLOADED` | File stored in S3, processing queued |
| `PROCESSING` | PDF extraction + embedding in progress |
| `PROCESSED` | Ready to query; `chunkCount` populated |
| `FAILED` | Pipeline error; check `errorMessage` field |

**Errors**
| Status | Condition |
|---|---|
| 413 | File exceeds 50 MB |
| 415 | Unsupported file type |
| 400 | Missing file or title |

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@report.pdf" \
  -F "title=Annual Report 2024"
```

---

### GET /documents

List all documents owned by the authenticated user, ordered by creation time descending.

**Query parameters**

| Param | Default | Description |
|---|---|---|
| `page` | 0 | Zero-based page index |
| `size` | 20 | Page size |
| `sort` | `createdAt,desc` | Sort field and direction |

**Response 200**
```json
{
  "data": {
    "content": [ { "id": "...", "title": "...", "status": "PROCESSED", ... } ],
    "totalElements": 42,
    "totalPages": 3,
    "number": 0,
    "size": 20
  },
  "success": true
}
```

```bash
curl "http://localhost:8080/api/v1/documents?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN"
```

---

### GET /documents/{id}

Get details of a single document. Returns 404 if the document does not belong to the caller.

```bash
curl http://localhost:8080/api/v1/documents/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
  -H "Authorization: Bearer $TOKEN"
```

**Errors**
| Status | Condition |
|---|---|
| 404 | Document not found or not owned by caller |

---

### DELETE /documents/{id}

Delete the document and cascade-delete all associated chunks, embeddings, and the S3 object.

**Response 204** — no body

```bash
curl -X DELETE http://localhost:8080/api/v1/documents/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
  -H "Authorization: Bearer $TOKEN"
```

---

## Q&A (One-Shot)

### POST /qa/ask

Single-turn RAG query. Retrieves the top-5 most similar document chunks, builds a grounded prompt, calls GPT-4o, and returns the answer with cited sources and a confidence score.

**Request**
```json
{
  "question": "What was the total revenue in Q3?",
  "documentIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `question` | string | Yes | Max 4,000 characters |
| `documentIds` | UUID[] | No | Scope search to specific docs; omit for all user docs |

**Response 200**
```json
{
  "data": {
    "conversationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "messageId": "a2fb4a1d-1a96-d312-4bf9-2f3577b34da6",
    "answer": "Q3 total revenue was $4.2B, a 12% year-over-year increase driven by strong performance in cloud services and subscription renewals.",
    "confidence": 0.87,
    "sources": [
      {
        "documentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "documentTitle": "Annual Report 2024",
        "chunkIndex": 42,
        "content": "Q3 revenue reached $4.2 billion, up from $3.75 billion in Q3 2023, reflecting...",
        "score": 0.91
      },
      {
        "documentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "documentTitle": "Annual Report 2024",
        "chunkIndex": 43,
        "content": "Cloud services contributed $1.8B to Q3 revenue, growing 28% year-over-year...",
        "score": 0.84
      }
    ],
    "createdAt": "2024-01-15T10:31:00Z"
  },
  "success": true
}
```

**confidence** — mean cosine similarity of retrieved source chunks (0.0–1.0). Higher indicates stronger grounding in the source material.

**score** per source — individual cosine similarity for that chunk (1 − cosine_distance).

```bash
curl -X POST http://localhost:8080/api/v1/qa/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What was the total revenue in Q3?",
    "documentIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"]
  }'
```

**Errors**
| Status | Condition |
|---|---|
| 404 | One or more documentIds not found or not owned by caller |
| 400 | question blank or exceeds 4,000 chars |

---

## Chat (Multi-Turn)

### POST /chat/ask

Stateful conversational query. On the first call (no `conversationId`), a new session is created. Subsequent calls with the `conversationId` replay up to 20 previous messages as context and retrieve fresh relevant chunks for the new question.

**Request**
```json
{
  "question": "Which of those risks increased the most year-over-year?",
  "conversationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "documentIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `question` | string | Yes | Max 4,000 characters |
| `conversationId` | UUID | No | Omit to start a new session |
| `documentIds` | UUID[] | No | Fixed scope for the session; null = all user docs |

**Response 200** — same shape as `/qa/ask` with `conversationId` referencing the session.

**Multi-turn example**

```bash
# 1. Start a conversation
RESP=$(curl -s -X POST http://localhost:8080/api/v1/chat/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question": "Summarise the key risks mentioned in the report."}')

CONV_ID=$(echo $RESP | jq -r '.data.conversationId')

# 2. Follow-up — the model has context from turn 1
curl -X POST http://localhost:8080/api/v1/chat/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"question\": \"Which of those risks increased the most year-over-year?\",
    \"conversationId\": \"$CONV_ID\"
  }"
```

---

### GET /chat/sessions

List all conversation sessions for the authenticated user, ordered by last activity descending.

**Query parameters** — same pagination as `GET /documents`

**Response 200**
```json
{
  "data": {
    "content": [
      {
        "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "title": "Summarise the key risks mentioned in t...",
        "messageCount": 6,
        "createdAt": "2024-01-15T10:30:00Z",
        "updatedAt": "2024-01-15T11:00:00Z"
      }
    ],
    "totalElements": 12,
    "totalPages": 1
  },
  "success": true
}
```

---

### GET /chat/sessions/{id}

Return a session with its complete message history, ordered chronologically.

**Response 200**
```json
{
  "data": {
    "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "title": "Summarise the key risks mentioned in t...",
    "messages": [
      {
        "id": "...",
        "role": "USER",
        "content": "Summarise the key risks mentioned in the report.",
        "createdAt": "2024-01-15T10:30:00Z"
      },
      {
        "id": "...",
        "role": "ASSISTANT",
        "content": "The report identifies three primary risk categories: ...",
        "createdAt": "2024-01-15T10:30:02Z"
      }
    ],
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T11:00:00Z"
  },
  "success": true
}
```

---

### DELETE /chat/sessions/{id}

Hard-delete a session and all its messages.

**Response 204** — no body

---

## Error Reference

All errors follow the same envelope:
```json
{
  "data": null,
  "error": "Human-readable message",
  "success": false
}
```

| HTTP Status | Cause |
|---|---|
| 400 Bad Request | Validation failure (blank question, invalid field) |
| 401 Unauthorized | Missing or expired JWT |
| 403 Forbidden | Valid JWT but accessing another user's resource |
| 404 Not Found | Document or session not found / not owned |
| 409 Conflict | Email already registered |
| 413 Payload Too Large | File exceeds 50 MB |
| 415 Unsupported Media Type | File type not in allowlist (PDF, DOCX, TXT, MD, CSV) |
| 500 Internal Server Error | Unexpected error (details in logs + trace ID) |

---

## Request Limits

| Limit | Value |
|---|---|
| Max file size | 50 MB |
| Max request size | 55 MB |
| Max question length | 4,000 characters |
| Max context chars per prompt | 8,000 characters (configurable) |
| Results returned per query | 5 chunks (configurable) |
| Chat history window | 20 messages (configurable) |
| JWT token lifetime | 24 hours |
| Presigned URL lifetime | 1 hour |

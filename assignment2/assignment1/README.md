# COMP3011 Assignment 1


## Project structure

```
src/main/java/com/example/demo/
  controller/   REST endpoints (Admin, GlobalStats, Transcription)
  service/      Business logic (calling OpenAI, tracking stats, uptime)
  model/        Response DTOs returned as JSON

src/main/resources/
  static/       Frontend (index.html, style.css, script.js)
  application.properties

src/test/java/com/example/demo/
  service/      StatsServiceTest
  controller/   TranscriptionControllerTest
```

## Running locally

Set the OpenAI API key as an environment variable, then start the app:

```bash
export OPENAI_API_KEY=your-real-key-here
./mvnw run
```

Open `http://localhost:8080/` and record something.

## Running the tests

```bash
./mvnw test
```

### What each test checks

- **`StatsServiceTest`**: fires 100 threads x 100 updates at the same time, then checks the final token counts are exactly right. Proves the token counters don't lose updates under concurrent load.

- **`TranscriptionControllerTest`**: starts a small fake server (using Java's built-in `HttpServer`), so the tests don't need real internet access or a real key. Two tests: one request through the full app, checking the returned text matches what the fake server sent back and 250 requests fired at the same time, checking they all succeed.


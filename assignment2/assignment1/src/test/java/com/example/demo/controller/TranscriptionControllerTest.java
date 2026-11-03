package com.example.demo.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import org.springframework.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.example.demo.service.TranscriptionService;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class TranscriptionControllerTest {
	
	@LocalServerPort
	private int port;
	
	private static HttpServer server;
	
	// Fake reply from the stub server
	private static final String STUB_RESPONSE_JSON = """
	    {
	        "text": "hello world",
	        "usage": {
	            "input_tokens": 5,
	            "output_tokens": 2
	        }
	    }
	    """;
	// Start a fake server before run tests
	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 300);
		
		// Create exchange context
		server.createContext("/v1/audio/transcriptions", exchange -> {
			byte[] resBytes = STUB_RESPONSE_JSON.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, resBytes.length);
			
			OutputStream os = exchange.getResponseBody();
			os.write(resBytes);
			os.close();
		});
		
		server.setExecutor(Executors.newFixedThreadPool(300));
		
		server.start();
	}
	
	// Stop server when all tests in this class are done
	@AfterAll
	static void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}
	
	// Build a fake audio file to send
	private HttpEntity<MultiValueMap<String, Object>> buildFakeAudio() {
		ByteArrayResource audio = new ByteArrayResource("fake-audio-bytes".getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "test-audio.webm";
			}
		};
		
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("audio", audio);
		
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		return new HttpEntity<>(body, headers);

	}
	
	// Tell system to use the fake server's url rather than the riel one
	@DynamicPropertySource
	static void overrideOpenAIconfig(DynamicPropertyRegistry registry) {
		int port = server.getAddress().getPort();
		registry.add("openai.base-url", () -> "http://localhost:" + port);
		registry.add("openai.api-key", () -> "fake-key");
	}
	
	// Send a request and check if we get the stub server's text back
	@Test
	void transcribeReturnsTextFromStubServerResponse() {
		TestRestTemplate rest = new TestRestTemplate();
		String url = "http://localhost:" + port + "/api/v1/transcribe";
		
		ResponseEntity<String> res = rest.postForEntity(url, buildFakeAudio(), String.class);
		
		if (res.getStatusCode().value() != 200 ) {
			throw new AssertionError("Expected status 200 but failed");
		}
		
		if (res.getBody() == null || !res.getBody().contains("hello world")) {
			throw new AssertionError("Expected response body but failed");
		}
	}
	
	// 250 request to check if the system can handle it
	@Test
	void handlesConcurrentTranscriptionRequest() throws InterruptedException {
		int REQUEST_COUNT = 250;
		String url = "http://localhost:" + port + "/api/v1/transcribe";
		TestRestTemplate rest = new TestRestTemplate();
		
		ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch end = new CountDownLatch(REQUEST_COUNT);
		
		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failedCount = new AtomicInteger(0);
		
		for (int i = 0; i < REQUEST_COUNT; i ++) {
			executor.submit(() -> {
				try {
					// Wait for every threads 
					start.await();
					
					ResponseEntity<String> res = rest.postForEntity(url, buildFakeAudio(), String.class);
					
					// Increment when sucesss and fail
					if (res.getStatusCode().value() == 200) {
						successCount.incrementAndGet();
					}
					else {
						failedCount.incrementAndGet();
					}
				} catch (Exception e) {
					failedCount.incrementAndGet();
					System.out.println("FAILED: " + e.getClass().getSimpleName());
				} finally {
					end.countDown();
				}
			});
		}
		
		// Release all threads at the same time
		start.countDown();
		
		// Wait up to 30s, it works should be correct i believe
		boolean allFinishedTime = end.await(30, TimeUnit.SECONDS);
		executor.shutdown();
		
		// If not finish in time, throw an error
		if (!allFinishedTime) {
			throw new AssertionError("Not all request finished on time!");
		}
		
		if (successCount.get() != REQUEST_COUNT) {
			throw new AssertionError(
					"Expected at " + REQUEST_COUNT + " requests "
					+ successCount.get() + " succeeded and " + failedCount.get() + " failed requests"
					);
		}
	}
	
	@Test
	void apiKeyisNotLeak() {
		// Attach a temp log 
		Logger log = (Logger) LoggerFactory.getLogger(TranscriptionService.class);
		ListAppender<ILoggingEvent> list = new ListAppender<>();
		list.start();
		log.addAppender(list);
		
		try {
			TestRestTemplate rest = new TestRestTemplate();
			String url = "http://localhost:" + port + "/api/v1/transcribe";
			
			rest.postForEntity(url, buildFakeAudio(), String.class);
			
			// The fake key value we set
			String key = "fake-key";
			
			for (ILoggingEvent e: list.list) {
				String mess = e.getFormattedMessage();
				
				if (mess.contains(key)) {
					throw new AssertionError("API key value was found in log message: " + mess);
				}
			}
			
		} finally {
			log.detachAppender(list);
		}
	}
	
}

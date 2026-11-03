package com.example.demo.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.demo.model.TranscriptionResponse;
import com.example.demo.service.TranscriptionService;

import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api/v1")
public class TranscriptionController {
	
	private final TranscriptionService transcriptionService;
	// Seprate pool for the blocking api call
	private final ExecutorService transcriptionExecutor = Executors.newFixedThreadPool(250);

	// Constructor
	public TranscriptionController(TranscriptionService transcriptionService) {
		this.transcriptionService = transcriptionService;
	}
	
	@PreDestroy
	public void shutdownExecutor() {
		transcriptionExecutor.shutdown();
	}
	
	// POST -> /api/v1/transcribe
	@PostMapping(
		value = "/transcribe", 
		consumes = MediaType.MULTIPART_FORM_DATA_VALUE
	)
	
	// Make service to understand HTTP error
	public CompletableFuture<ResponseEntity<?>> transcribe(@RequestParam("audio") MultipartFile audio) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String text = transcriptionService.transcribe(audio);
				return ResponseEntity.ok(new TranscriptionResponse(text));	
			} catch (Exception e) {
				return ResponseEntity.status(500)
					.body("""
						{
							"timestamp": "%s",
							"status": 500,
							"error": "Internal Server Error",
							"message": "An unexpected server error occurred.",
							"path": "/api/v1/transcribe"
						}
						""".formatted(java.time.Instant.now()));
			}

		}, this.transcriptionExecutor);
	}

	// POST -> /api/v1/subtitles
	private static ResponseEntity<?> subtitleError(int status, String error, String message) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body("""
				{
					"timestamp": "%s",
					"status": %d,
					"error": "%s",
					"message": "%s",
					"path": "/api/v1/subtitles"
				}
				""".formatted(java.time.Instant.now(), status, error, message));
	}
}

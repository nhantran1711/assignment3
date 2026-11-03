package com.example.demo.service;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.annotation.JsonProperty;

@Service
public class TranscriptionService {
	
	
	private final String apiKey;
	private final RestClient restClient;
	private final StatsService statsService;
	private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

	// Constructor
	public TranscriptionService(@Value("${openai.api-key}") String apiKey, @Value("${openai.base-url}") String baseUrl, StatsService statsService) {
		this.apiKey = apiKey;
		HttpClient httpclient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpclient); 
		factory.setReadTimeout(Duration.ofSeconds(15));
		
		this.restClient = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory)
				.build();
		this.statsService = statsService;
	}
	
	// Transcribe audio
	public String transcribe(MultipartFile audio) {
		
		// Log transcription
		log.info("Received transcription request. Audio size: " + audio.getSize() + " bytes");
		
		try {
			MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
			
			map.add("model", "gpt-4o-mini-transcribe");
			map.add("file", audio.getResource());
			
			// Response from OpenAI object
			OpenAITranscriptionResponse res = restClient.post()
					.uri("/v1/audio/transcriptions")
					.header("Authorization", "Bearer " + this.apiKey)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(map)
					.retrieve()
					.body(OpenAITranscriptionResponse.class);
			
			statsService.addTokens(
			        res.usage().inputTokens(),
			        res.usage().outputTokens()
			);
			
			log.info("Transcription succeeded!. Input tokens: " + res.usage().inputTokens() + ". Output tokens: " + res.usage().outputTokens());
			
			return res.text();		
			
		} catch (Exception error) {
			log.error("Transcription request to OpenAI failed", error);
			throw new RuntimeException("Request failed ", error);
		}

	}
	
	private record OpenAITranscriptionResponse(String text, Usage usage) {};
	
	private record Usage(@JsonProperty("input_tokens") long inputTokens, @JsonProperty("output_tokens") long outputTokens) {}
	
	
}

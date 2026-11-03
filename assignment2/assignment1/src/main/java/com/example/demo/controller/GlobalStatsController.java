package com.example.demo.controller;

import com.example.demo.model.GlobalStatsResponse;
import com.example.demo.service.StatsService;

import java.util.Map;

import org.springframework.http.ResponseEntity;
// REST Frameworks
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/global")
public class GlobalStatsController {
	
	private final StatsService statsService;
	
	// Constructor
	public GlobalStatsController(StatsService statsService) {
		this.statsService = statsService;
	}
	
	// GET -> /stats
	@GetMapping("/stats")
	public ResponseEntity<?> getStats() {
		try {
			return ResponseEntity.ok(statsService.getStats());	
		} catch (Exception e) {
	        return ResponseEntity.status(500)
                .body("""
                    {
                        "timestamp": "%s",
                        "status": 500,
                        "error": "Internal Server Error",
                        "message": "An unexpected server error occurred.",
                        "path": "/api/v1/global/stats"
                    }
                    """.formatted(java.time.Instant.now()));
		}
	}

	// GET -> /usage  (total whisper seconds used, { "usage": s })
	@GetMapping("/usage")
	public ResponseEntity<Map<String, Double>> getUsage() {
		return ResponseEntity.ok(Map.of("usage", statsService.getUsageSeconds()));
	}
}

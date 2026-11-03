package com.example.demo.controller;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
// REST frameworks
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.UpTimeService;
import com.example.demo.model.UpTimeResponse;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private final UpTimeService upTimeService;
	private final ConfigurableApplicationContext context;
	private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
	
	// Constructor
	public AdminController(UpTimeService upTimeService, ConfigurableApplicationContext context) {
		this.upTimeService = upTimeService;
		this.context = context;
	}
	
	// GET -> /api/v1/admin/uptime
	@GetMapping("/uptime")
	public ResponseEntity<?> getUpTime() {
		try {
			return ResponseEntity.ok(upTimeService.getUpTime());
		} catch (Exception e ) {
			return ResponseEntity.status(500)
				.body("""
					{
						"timestamp": "%s",
						"status": 500,
						"error": "Internal Server Error",
						"message": "An unexpected server error occurred.",
						"path": "/api/v1/admin/uptime"
					}
					""".formatted(java.time.Instant.now()));
		}

	}
	
	// POST -> /api/v1/admin/shutdown
	@PostMapping("/shutdown")
	public ResponseEntity<String> shutdown() {
		
		// If we already have shutdown thread, return 409
		if (!isShuttingDown.compareAndSet(false, true)) {
			return ResponseEntity.status(409)
	            .body("""
                    {
                        "timestamp": "%s",
                        "status": 409,
                        "error": "Conflict",
                        "message": "Graceful shutdown is already in progress.",
                        "path": "/api/v1/admin/shutdown"
                    }
                    """.formatted(java.time.Instant.now()));
		}
		
		new Thread(() -> {
			try {
				Thread.sleep(100);
				context.close();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			
		}).start();
		
		return ResponseEntity.accepted()
				.body("{\"message\":\"Graceful shutdown requested.\"}");
	}
	
}

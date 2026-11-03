package com.example.demo.service;

import java.time.Instant;
import java.time.Duration;

import org.springframework.stereotype.Service;
import com.example.demo.model.UpTimeResponse;

@Service
public class UpTimeService {
	
	private final Instant serverStartTime;
	
	// Constructor
	public UpTimeService() {
		this.serverStartTime = Instant.now();
	}
	
	// Calculate server up time
	public UpTimeResponse getUpTime() {
		Instant now = Instant.now();
		
		double upTime = Duration.between(serverStartTime, now).toNanos() / 1_000_000_000.0;
		
		return new UpTimeResponse(serverStartTime.toString(), now.toString(), upTime);
	}
}

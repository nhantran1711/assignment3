package com.example.demo.model;

// Response class for UpTimeService
public class UpTimeResponse {
	
	private String utcServerStart;
	private String utcNow;
	private double serverUptimeSeconds;
	
	// Constructor
	public UpTimeResponse(String utcServerStart, String utcNow, double serverUptimeSeconds) {
		this.utcServerStart = utcServerStart;
		this.utcNow = utcNow;
		this.serverUptimeSeconds = serverUptimeSeconds;
	}
	
	// Get utc server start
	public String getUtcServerStart() {
		return this.utcServerStart;
	}
	
	// Get utc server now
	public String getUtcNow() {
		return this.utcNow;
	}
	
	// Get server up time in second
	public double getServerUptimeSeconds() {
		return this.serverUptimeSeconds;
	}
}

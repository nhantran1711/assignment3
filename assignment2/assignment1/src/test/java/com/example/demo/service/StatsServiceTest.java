package com.example.demo.service;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;


public class StatsServiceTest {
	
	
	@Test
	void tokenUpdatesShouldNotLoseTokens() throws InterruptedException {
		StatsService stats = new StatsService();
		
		int threads = 100;
		int updatesPerThread = 100;
		
		// executor, start and finish latching countdown
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch end = new CountDownLatch(threads);
		
		// Create all worker threads
		for (int i = 0; i < threads; i++) {
			executor.submit(() -> {
				
				// Wait for all threads to start
				try {
					start.await();
				} catch (InterruptedException e) {
					throw new AssertionError("Thread was interrupted", e);
				}
				
				for (int j = 0; j < updatesPerThread;j ++) {
					stats.addTokens(10, 5);
				}
				
				// this thread has done
				end.countDown();
				
			});
		}
		
		start.countDown();
		end.await();
		executor.shutdown();
		
		// Calculate
		long expectedInput = (long) threads * updatesPerThread * 10;
		long expectedOutput = (long) threads * updatesPerThread * 5;
		
		if (stats.getStats().getInputTokens() != expectedInput) {
		    throw new AssertionError(
		        "Expected input tokens: " + expectedInput +
		        ", but got: " + stats.getStats().getInputTokens()
		    );
		}

		if (stats.getStats().getOutputTokens() != expectedOutput) {
		    throw new AssertionError(
		        "Expected output tokens: " + expectedOutput +
		        ", but got: " + stats.getStats().getOutputTokens()
		    );
		}
	}
	
}

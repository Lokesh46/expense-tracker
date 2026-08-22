package com.lokesh_codes.expense_tracker_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Drives the nightly sweep that materialises due recurring transactions.
@EnableScheduling
public class ExpenseTrackerBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExpenseTrackerBackendApplication.class, args);
	}

}

package com.hospitalqueue;

import org.springframework.boot.SpringApplication;

public class TestHospitalQueueApplication {

	public static void main(String[] args) {
		SpringApplication.from(HospitalQueueApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

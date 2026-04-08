package org.vivek.marketdataservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MarketDataServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarketDataServiceApplication.class, args);
	}

}

package com.loganalyzer;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAdminServer
@EnableScheduling
public class LogAnalyzerApplication {

    public static void main(String[] args) {
        //main - test commit
        SpringApplication.run(LogAnalyzerApplication.class, args);
    }
}

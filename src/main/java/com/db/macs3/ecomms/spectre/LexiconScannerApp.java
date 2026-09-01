package com.db.macs3.ecomms.spectre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;

/**
 * Entry point for the Lexicon Scanner Service.
 *
 * <p>Provides a web UI (served from {@code /static/index.html}) and a REST API
 * at {@code /api/scan} for testing lexicon patterns against communication messages
 * using Intel's Hyperscan PCRE engine.
 *
 * <p>{@link ServletComponentScan} is required for {@link jakarta.servlet.annotation.WebFilter}
 * on {@code GzipRequestFilter} to be detected automatically.
 *
 * <p>Run locally: {@code mvn spring-boot:run}
 * Docker: build the JAR with {@code mvn package -DskipTests}, then {@code docker build}.
 */
@SpringBootApplication
@ServletComponentScan
public class LexiconScannerApp {

    public static void main(String[] args) {
        SpringApplication.run(LexiconScannerApp.class, args);
    }
}

package com.db.macs3.ecomms.spectre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed configuration properties for the Lexicon Scanner Service.
 *
 * <p>Bound from the {@code lexicon.*} prefix in {@code application.yml}.
 * All properties have safe defaults so the service starts in development
 * mode without any configuration file.
 *
 * <p>Example {@code application.yml}:
 * <pre>{@code
 * lexicon:
 *   compile-service:
 *     base-url: http://lexicon-compile-service:8080
 *     connect-timeout: PT5S
 *     read-timeout: PT30S
 *   scanner:
 *     max-terms: 500
 *     max-message-length: 500000
 *     default-rule-name: scanner-test-run
 * }</pre>
 */
@ConfigurationProperties(prefix = "lexicon")
public class AppProperties {

    private final CompileService compileService = new CompileService();
    private final Scanner scanner = new Scanner();

    /**
     * Returns configuration for the upstream Lexicon Compile Service client.
     */
    public CompileService getCompileService() {
        return compileService;
    }

    /**
     * Returns configuration for the scanner (Hyperscan) pipeline.
     */
    public Scanner getScanner() {
        return scanner;
    }

    // ── Nested classes ────────────────────────────────────────────────────────

    /**
     * HTTP client configuration for the Lexicon Compile Service.
     */
    public static class CompileService {

        private String baseUrl = "http://localhost:8080";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);
        private String defaultRuleName = "scanner-test-rule";

        /** Base URL of the Lexicon Compile Service REST API. */
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        /** TCP connection timeout when reaching the Lexicon Compile Service. */
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

        /** Read timeout for the compile HTTP response. */
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

        /** Default {@code lexiconRuleName} sent when no rule name is supplied by the caller. */
        public String getDefaultRuleName() { return defaultRuleName; }
        public void setDefaultRuleName(String defaultRuleName) { this.defaultRuleName = defaultRuleName; }
    }

    /**
     * Scanner (Hyperscan) pipeline configuration.
     */
    public static class Scanner {

        private int maxTerms = 500;
        private int maxMessageLength = 500_000;
        private boolean fallbackToJavaRegex = true;
        private String riskDriverDefault = "Compliance Scan";

        /** Maximum number of lexicon terms accepted per scan request. */
        public int getMaxTerms() { return maxTerms; }
        public void setMaxTerms(int maxTerms) { this.maxTerms = maxTerms; }

        /** Maximum allowed length (chars) of the message text input. */
        public int getMaxMessageLength() { return maxMessageLength; }
        public void setMaxMessageLength(int maxMessageLength) { this.maxMessageLength = maxMessageLength; }

        /**
         * When true, the scanner falls back to Java regex matching if
         * Hyperscan's {@code Scanner.scan()} fails at runtime.
         * Useful for test environments where the native scratch allocator
         * behaves differently.
         */
        public boolean isFallbackToJavaRegex() { return fallbackToJavaRegex; }
        public void setFallbackToJavaRegex(boolean fallbackToJavaRegex) {
            this.fallbackToJavaRegex = fallbackToJavaRegex;
        }

        /** Default risk driver name applied when a term row has no risk driver column. */
        public String getRiskDriverDefault() { return riskDriverDefault; }
        public void setRiskDriverDefault(String riskDriverDefault) {
            this.riskDriverDefault = riskDriverDefault;
        }
    }
}

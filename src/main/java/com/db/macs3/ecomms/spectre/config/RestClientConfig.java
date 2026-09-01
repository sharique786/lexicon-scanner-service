package com.db.macs3.ecomms.spectre.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC and HTTP client configuration for the Lexicon Scanner Service.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Creates the {@link RestClient} bean pre-configured to call the
 *       Lexicon Compile Service with correct timeouts and base URL.</li>
 *   <li>Configures CORS so the UI can be embedded as a micro-frontend
 *       in any origin that needs to call the scanner REST API.</li>
 *   <li>Registers static resource handlers so {@code index.html} and
 *       its assets are served from {@code /static/}.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class RestClientConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    private final AppProperties props;

    public RestClientConfig(AppProperties props) {
        this.props = props;
    }

    /**
     * {@link RestClient} pre-configured for the upstream Lexicon Compile Service.
     *
     * <p>Timeouts are sourced from {@code lexicon.compile-service.*} in
     * {@code application.yml}. Both connect and read timeouts default to
     * 5 s and 30 s respectively, which are generous enough to handle
     * cold Hyperscan compilation of large lexicons.
     */
    @Bean
    public RestClient lexiconCompileRestClient() {
        AppProperties.CompileService cs = props.getCompileService();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cs.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) cs.getReadTimeout().toMillis());

        log.info("Lexicon Compile Service base URL: {}", cs.getBaseUrl());

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(cs.getBaseUrl())
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ── WebMvcConfigurer: CORS + static resources ─────────────────────────────

    /**
     * CORS: Allow all origins so the scanner UI can be embedded as a
     * micro-frontend in any host web application without CORS errors.
     * Restrict to scanner-specific API paths only.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * Serve static assets (index.html, CSS, JS) from
     * {@code src/main/resources/static/} under the root path.
     * This allows the single-page UI to be loaded at {@code /} or {@code /index.html}.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);
    }
}

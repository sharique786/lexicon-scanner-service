package com.db.macs3.ecomms.spectre.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Servlet filter that transparently decompresses {@code Content-Encoding: gzip}
 * request bodies before the request reaches any controller.
 *
 * <p>Only requests with {@code Content-Encoding: gzip} are processed;
 * all other requests pass through without modification.
 *
 * <p>Applies to every request path (registered via {@link OncePerRequestFilter}
 * so it fires at most once per request regardless of forward chains).
 */
@Component
@WebFilter("/*")
public class GzipRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GzipRequestFilter.class);
    private static final String GZIP_ENCODING = "gzip";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String contentEncoding = request.getHeader("Content-Encoding");

        if (GZIP_ENCODING.equalsIgnoreCase(contentEncoding)) {
            log.debug("Decompressing gzip request body for {} {}", request.getMethod(), request.getRequestURI());
            try {
                GzipRequestWrapper wrapper = new GzipRequestWrapper(request);
                chain.doFilter(wrapper, response);
            } catch (IOException e) {
                log.error("Failed to decompress gzip request body: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Request body decompression failed: " + e.getMessage());
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    // ── Inner wrapper ─────────────────────────────────────────────────────────

    /**
     * {@link HttpServletRequestWrapper} that decompresses the gzip request body
     * into an in-memory byte array, then serves it via a fresh stream on each
     * call to {@link #getInputStream()}.
     */
    private static final class GzipRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] decompressedBody;

        GzipRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            try (GZIPInputStream gzip = new GZIPInputStream(request.getInputStream())) {
                this.decompressedBody = gzip.readAllBytes();
            }
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(decompressedBody);
            return new ServletInputStream() {
                @Override public int read() throws IOException { return bais.read(); }
                @Override public boolean isFinished() { return bais.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async not supported");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream()));
        }

        /** Returns the actual decompressed content length, not the compressed one. */
        @Override
        public int getContentLength() { return decompressedBody.length; }

        @Override
        public long getContentLengthLong() { return decompressedBody.length; }
    }
}

package com.falkordb.tracing;

import com.falkordb.jena.tracing.TracingUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Servlet filter that adds OpenTelemetry tracing to all Fuseki HTTP requests.
 *
 * <p>This provides Level 1 tracing: top-level Fuseki calls with all
 * HTTP request information including method, path, and query parameters.</p>
 */
public final class FusekiTracingFilter implements Filter {
    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(
        FusekiTracingFilter.class);

    /** Tracer for Fuseki request tracing. */
    private Tracer tracer;

    /** Attribute key for HTTP method. */
    private static final AttributeKey<String> ATTR_HTTP_METHOD =
        AttributeKey.stringKey("http.method");

    /** Attribute key for HTTP URL. */
    private static final AttributeKey<String> ATTR_HTTP_URL =
        AttributeKey.stringKey("http.url");

    /** Attribute key for HTTP route/path. */
    private static final AttributeKey<String> ATTR_HTTP_ROUTE =
        AttributeKey.stringKey("http.route");

    /** Attribute key for HTTP status code. */
    private static final AttributeKey<Long> ATTR_HTTP_STATUS_CODE =
        AttributeKey.longKey("http.status_code");

    /** Attribute key for HTTP query string. */
    private static final AttributeKey<String> ATTR_HTTP_QUERY =
        AttributeKey.stringKey("http.query_string");

    /** Attribute key for SPARQL query. */
    private static final AttributeKey<String> ATTR_SPARQL_QUERY =
        AttributeKey.stringKey("sparql.query");

    /** Attribute key for content type. */
    private static final AttributeKey<String> ATTR_HTTP_CONTENT_TYPE =
        AttributeKey.stringKey("http.request.content_type");

    /** Attribute key for user agent. */
    private static final AttributeKey<String> ATTR_HTTP_USER_AGENT =
        AttributeKey.stringKey("http.user_agent");

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException {
        this.tracer = TracingUtil.getTracer(TracingUtil.SCOPE_FUSEKI);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("FusekiTracingFilter initialized");
        }
    }

    @Override
    public void doFilter(final ServletRequest request,
                         final ServletResponse response,
                         final FilterChain chain)
            throws IOException, ServletException {

        if (!TracingUtil.isTracingEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String method = httpRequest.getMethod();
        String path = httpRequest.getRequestURI();
        String queryString = httpRequest.getQueryString();
        String spanName = method + " " + path;

        Span span = tracer.spanBuilder(spanName)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(ATTR_HTTP_METHOD, method)
            .setAttribute(ATTR_HTTP_ROUTE, path)
            .startSpan();

        // Add full URL
        StringBuffer urlBuffer = httpRequest.getRequestURL();
        if (queryString != null) {
            urlBuffer.append("?").append(queryString);
        }
        span.setAttribute(ATTR_HTTP_URL, urlBuffer.toString());

        // Add query string if present
        if (queryString != null) {
            span.setAttribute(ATTR_HTTP_QUERY, queryString);
        }

        // Add content type if present
        String contentType = httpRequest.getContentType();
        if (contentType != null) {
            span.setAttribute(ATTR_HTTP_CONTENT_TYPE, contentType);
        }

        // Add user agent if present
        String userAgent = httpRequest.getHeader("User-Agent");
        if (userAgent != null) {
            span.setAttribute(ATTR_HTTP_USER_AGENT, userAgent);
        }

        // Try to extract SPARQL query from request parameters
        String sparqlQuery = httpRequest.getParameter("query");
        if (sparqlQuery != null && !sparqlQuery.isEmpty()) {
            span.setAttribute(ATTR_SPARQL_QUERY, sparqlQuery);
        }

        try (Scope scope = span.makeCurrent()) {
            chain.doFilter(request, response);

            // Set status code after processing
            int statusCode = httpResponse.getStatus();
            span.setAttribute(ATTR_HTTP_STATUS_CODE, (long) statusCode);

            if (statusCode >= 400) {
                span.setStatus(StatusCode.ERROR,
                    "HTTP " + statusCode);
            } else {
                span.setStatus(StatusCode.OK);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public void destroy() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("FusekiTracingFilter destroyed");
        }
    }
}

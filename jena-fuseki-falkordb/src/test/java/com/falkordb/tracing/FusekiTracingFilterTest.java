package com.falkordb.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FusekiTracingFilter class.
 */
public class FusekiTracingFilterTest {

    private FusekiTracingFilter filter;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private FilterChain mockChain;
    private FilterConfig mockConfig;

    @BeforeEach
    public void setUp() throws ServletException {
        filter = new FusekiTracingFilter();
        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);
        mockChain = mock(FilterChain.class);
        mockConfig = mock(FilterConfig.class);

        // Initialize the filter
        filter.init(mockConfig);
    }

    @Test
    @DisplayName("Test filter initialization does not throw")
    public void testInit() {
        FusekiTracingFilter newFilter = new FusekiTracingFilter();
        assertDoesNotThrow(() -> newFilter.init(mockConfig));
    }

    @Test
    @DisplayName("Test filter destroy does not throw")
    public void testDestroy() {
        assertDoesNotThrow(() -> filter.destroy());
    }

    @Test
    @DisplayName("Test doFilter with GET request")
    public void testDoFilterGetRequest() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("GET");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/query");
        when(mockRequest.getQueryString()).thenReturn("query=SELECT%20*");
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/query"));
        when(mockResponse.getStatus()).thenReturn(200);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
    }

    @Test
    @DisplayName("Test doFilter with POST request")
    public void testDoFilterPostRequest() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("POST");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/update");
        when(mockRequest.getQueryString()).thenReturn(null);
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/update"));
        when(mockRequest.getContentType()).thenReturn("application/sparql-update");
        when(mockResponse.getStatus()).thenReturn(200);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
    }

    @Test
    @DisplayName("Test doFilter with SPARQL query parameter")
    public void testDoFilterWithSparqlQuery() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("GET");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/query");
        when(mockRequest.getQueryString()).thenReturn("query=SELECT%20*");
        when(mockRequest.getParameter("query")).thenReturn("SELECT * WHERE { ?s ?p ?o }");
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/query"));
        when(mockResponse.getStatus()).thenReturn(200);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
        verify(mockRequest).getParameter("query");
    }

    @Test
    @DisplayName("Test doFilter with user agent header")
    public void testDoFilterWithUserAgent() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("GET");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/query");
        when(mockRequest.getQueryString()).thenReturn(null);
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/query"));
        when(mockRequest.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
        when(mockResponse.getStatus()).thenReturn(200);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
        verify(mockRequest).getHeader("User-Agent");
    }

    @Test
    @DisplayName("Test doFilter with error status code")
    public void testDoFilterWithErrorStatus() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("GET");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/query");
        when(mockRequest.getQueryString()).thenReturn(null);
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/query"));
        when(mockResponse.getStatus()).thenReturn(500);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
    }

    @Test
    @DisplayName("Test doFilter with 404 status code")
    public void testDoFilterWith404Status() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("GET");
        when(mockRequest.getRequestURI()).thenReturn("/nonexistent");
        when(mockRequest.getQueryString()).thenReturn(null);
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/nonexistent"));
        when(mockResponse.getStatus()).thenReturn(404);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
    }

    @Test
    @DisplayName("Test doFilter propagates exceptions from chain")
    public void testDoFilterPropagatesException() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("GET");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/query");
        when(mockRequest.getQueryString()).thenReturn(null);
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/query"));

        doThrow(new ServletException("Test exception"))
            .when(mockChain).doFilter(mockRequest, mockResponse);

        assertThrows(ServletException.class,
            () -> filter.doFilter(mockRequest, mockResponse, mockChain));
    }

    @Test
    @DisplayName("Test default constructor creates valid instance")
    public void testDefaultConstructor() {
        FusekiTracingFilter newFilter = new FusekiTracingFilter();
        assertNotNull(newFilter);
    }

    @Test
    @DisplayName("Test doFilter with content type")
    public void testDoFilterWithContentType() throws IOException, ServletException {
        when(mockRequest.getMethod()).thenReturn("POST");
        when(mockRequest.getRequestURI()).thenReturn("/falkor/data");
        when(mockRequest.getQueryString()).thenReturn(null);
        when(mockRequest.getRequestURL()).thenReturn(
            new StringBuffer("http://localhost:3330/falkor/data"));
        when(mockRequest.getContentType()).thenReturn("text/turtle");
        when(mockResponse.getStatus()).thenReturn(201);

        filter.doFilter(mockRequest, mockResponse, mockChain);

        verify(mockChain).doFilter(mockRequest, mockResponse);
        verify(mockRequest).getContentType();
    }
}

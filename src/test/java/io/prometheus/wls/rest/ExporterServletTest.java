package io.prometheus.wls.rest;
/*
 * Copyright (c) 2017 Oracle and/or its affiliates
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.
 */
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.prometheus.wls.rest.HttpServletRequestStub.createGetRequest;
import static io.prometheus.wls.rest.HttpServletResponseStub.createServletResponse;
import static io.prometheus.wls.rest.InMemoryFileSystem.withNoParams;
import static io.prometheus.wls.rest.ServletConstants.*;
import static io.prometheus.wls.rest.domain.JsonPathMatcher.hasJsonPath;
import static io.prometheus.wls.rest.matchers.CommentsOnlyMatcher.containsOnlyComments;
import static io.prometheus.wls.rest.matchers.MetricsNamesSnakeCaseMatcher.usesSnakeCase;
import static io.prometheus.wls.rest.matchers.PrometheusMetricsMatcher.followsPrometheusRules;
import static io.prometheus.wls.rest.matchers.ResponseHeaderMatcher.containsHeader;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;

/**
 * @author Russell Gold
 */
public class ExporterServletTest {
    private static final String URL_PATTERN = "http://%s:%d/management/weblogic/latest/serverRuntime/search";
    private static final String ONE_VALUE_CONFIG = "queries:\n- groups:\n    key: name\n    values: testSample1";
    private static final String TWO_VALUE_CONFIG = "queries:" +
            "\n- groups:\n    prefix: groupValue_\n    key: name\n    values: [testSample1,testSample2]";
    private static final String CONFIG_WITH_CATEGORY_VALUE = "queries:" +
            "\n- groups:\n    prefix: groupValue_\n    key: name\n    values: [testSample1, testSample2, bogus]";
    private static final String MULTI_QUERY_CONFIG = "queries:" +
            "\n- groups:\n    prefix: groupValue_\n    key: name\n    values: [testSample1,testSample2]" +
            "\n- colors:                         \n    key: hue \n    values: wavelength";
    private WebClientFactoryStub factory = new WebClientFactoryStub();
    private ExporterServlet servlet = new ExporterServlet(factory);
    private HttpServletRequestStub request = createGetRequest();
    private HttpServletResponseStub response = createServletResponse();

    @Before
    public void setUp() throws Exception {
        InMemoryFileSystem.install();
        ConfigurationUpdaterStub.install();
        LiveConfiguration.loadFromString("");
        LiveConfiguration.setServer("localhost", 7001);
    }

    @After
    public void tearDown() throws Exception {
        InMemoryFileSystem.uninstall();
        ConfigurationUpdaterStub.uninstall();
    }

    @Test
    public void exporter_isHttpServlet() throws Exception {
        assertThat(servlet, instanceOf(HttpServlet.class));
    }

    @Test
    public void servlet_hasWebServletAnnotation() throws Exception {
        assertThat(ExporterServlet.class.getAnnotation(WebServlet.class), notNullValue());
    }

    @Test
    public void servletAnnotationIndicatesMetricsPage() throws Exception {
        WebServlet annotation = ExporterServlet.class.getAnnotation(WebServlet.class);

        assertThat(annotation.value(), arrayContaining("/metrics"));
    }

    @Test
    public void whenConfigParamNotFound_configurationHasNoQueries() throws Exception {
        servlet.init(withNoParams());

        servlet.doGet(request, response);

        assertThat(LiveConfiguration.hasQueries(), is(false));
    }

    @Test
    public void whenConfigFileNameNotAbsolute_getReportsTheIssue() throws Exception {
        servlet.init(withNoParams());

        servlet.doGet(request, response);

        assertThat(toHtml(response), containsString("# No configuration"));
    }

    @Test
    public void whenConfigFileNotFound_getReportsTheIssue() throws Exception {
        servlet.init(withNoParams());

        servlet.doGet(request, response);

        assertThat(toHtml(response), containsString("# No configuration"));
    }

    @Test
    public void onGet_defineConnectionUrlFromContext() throws Exception {
        initServlet("");

        servlet.doGet(request, response);
        assertThat(factory.getClientUrl(), equalTo(String.format(URL_PATTERN, HttpServletRequestStub.HOST, HttpServletRequestStub.PORT)));
    }

    @Test
    public void whenServerSends403StatusOnGet_returnToClient() throws Exception {
        initServlet(ONE_VALUE_CONFIG);

        factory.reportNotAuthorized();
        servlet.doGet(request, response);

        assertThat(response.getStatus(), equalTo(NOT_AUTHORIZED));
    }

    @Test
    public void whenServerSends401StatusOnGet_returnToClient() throws Exception {
        initServlet(ONE_VALUE_CONFIG);

        factory.reportAuthenticationRequired("Test-Realm");
        servlet.doGet(request, response);

        assertThat(response.getStatus(), equalTo(AUTHENTICATION_REQUIRED));
        assertThat(response, containsHeader("WWW-Authenticate", "Basic realm=\"Test-Realm\""));
    }

    @Test
    public void whenServerSendsSetCookieHeader_returnToClient() throws Exception {
        final String SET_COOKIE_HEADER = "ACookie=AValue; Secure";

        factory.setCookieResponseHeader(SET_COOKIE_HEADER);
        factory.addJsonResponse(getGroupResponseMap());
        initServlet(TWO_VALUE_CONFIG);

        servlet.doGet(request, response);

        assertThat(response, containsHeader("Set-Cookie", SET_COOKIE_HEADER));
    }

    @Test
    public void whenClientSendsAuthenticationHeaderOnGet_passToServer() throws Exception {
        initServlet(ONE_VALUE_CONFIG);

        request.setHeader("Authorization", "auth-credentials");
        servlet.doGet(request, response);

        assertThat(factory.getSentHeader("Authorization"), equalTo("auth-credentials"));
    }

    @Test
    public void whenClientSendsCookieHeaderOnGet_passToServer() throws Exception {
        initServlet(ONE_VALUE_CONFIG);

        request.setHeader("Cookie", "with-chocolate-chips");
        servlet.doGet(request, response);

        assertThat(factory.getSentHeader("Cookie"), equalTo("with-chocolate-chips"));
    }

    @Test
    public void onGet_sendJsonQuery() throws Exception {
        initServlet(ONE_VALUE_CONFIG);

        servlet.doGet(request, response);

        assertThat(factory.getSentQuery(),
                   hasJsonPath("$.children.groups.fields").withValues("name", "testSample1"));
    }

    private void initServlet(String configuration) throws ServletException {
        InMemoryFileSystem.defineResource(LiveConfiguration.CONFIG_YML, configuration);
        servlet.init(withNoParams());
    }

    @Test
    public void onGet_displayMetrics() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        initServlet(TWO_VALUE_CONFIG);

        servlet.doGet(request, response);

        assertThat(toHtml(response), containsString("groupValue_testSample1{name=\"first\"} 12"));
        assertThat(toHtml(response), containsString("groupValue_testSample1{name=\"second\"} -3"));
        assertThat(toHtml(response), containsString("groupValue_testSample2{name=\"second\"} 71.0"));
    }

    private String toHtml(HttpServletResponseStub response) {
        return response.getHtml();
    }

    private Map getGroupResponseMap() {
        return ImmutableMap.of("groups", new ItemHolder(
                    ImmutableMap.of("name", "first", "testSample1", 12, "testSample2", 12.3, "bogus", "red"),
                    ImmutableMap.of("name", "second", "testSample1", -3, "testSample2", 71.0),
                    ImmutableMap.of("name", "third", "testSample1", 85, "testSample2", 65.8)
        ));
    }

    @Test
    public void whenNewConfigAvailable_loadBeforeGeneratingMetrics() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        initServlet(ONE_VALUE_CONFIG);
        ConfigurationUpdaterStub.newConfiguration(1, TWO_VALUE_CONFIG);

        servlet.doGet(request, response);

        assertThat(toHtml(response), containsString("groupValue_testSample2{name=\"second\"} 71.0"));
    }

    @Test
    public void onGet_displayMetricsInSnakeCase() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        initServlet("metricsNameSnakeCase: true\nqueries:\n- groups:\n" +
                "    prefix: groupValue_\n    key: name\n    values: [testSample1,testSample2]");

        servlet.doGet(request, this.response);

        assertThat(toHtml(this.response), usesSnakeCase());
    }

    @Test
    public void onGet_metricsArePrometheusCompliant() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        initServlet(CONFIG_WITH_CATEGORY_VALUE);

        servlet.doGet(request, response);

        assertThat(toHtml(response), followsPrometheusRules());
    }

    @Test
    public void onGet_producePerformanceMetrics() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        initServlet(CONFIG_WITH_CATEGORY_VALUE);

        servlet.doGet(request, response);

        assertThat(toHtml(response), containsString("wls_scrape_mbeans_count_total{instance=\"myhost:7654\"} 6"));
    }

    @Test
    public void onGetWithMultipleQueries_displayMetrics() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        factory.addJsonResponse(getColorResponseMap());
        initServlet(MULTI_QUERY_CONFIG);

        servlet.doGet(request, this.response);

        assertThat(toHtml(this.response), containsString("groupValue_testSample1{name=\"first\"} 12"));
        assertThat(toHtml(this.response), containsString("groupValue_testSample1{name=\"second\"} -3"));
        assertThat(toHtml(this.response), containsString("wavelength{hue=\"green\"} 540"));
    }

    private Map getColorResponseMap() {
        return ImmutableMap.of("colors", new ItemHolder(
                    ImmutableMap.of("hue", "red", "wavelength", 700),
                    ImmutableMap.of("hue", "green", "wavelength", 540),
                    ImmutableMap.of("hue", "blue", "wavelength", 475)
        ));
    }

    @Test
    public void whenNoQueries_produceNoOutput() throws Exception {
        initServlet("");

        servlet.doGet(request, response);

        assertThat(toHtml(response), containsOnlyComments());
    }

    @Test
    public void whenNoConfiguration_produceNoOutput() throws Exception {
        servlet.doGet(request, response);

        assertThat(toHtml(response), containsOnlyComments());
    }

    @Test
    public void whenKeyAlsoListedAsValue_dontDisplayIt() throws Exception {
        factory.addJsonResponse(getGroupResponseMap());
        initServlet("queries:" +
                "\n- groups:\n    prefix: groupValue_\n    key: testSample1\n    values: [testSample1]");

        servlet.doGet(request, response);

        assertThat(toHtml(this.response), not(containsString("groupValue_testSample1{testSample1")));
    }

    static class WebClientFactoryStub implements WebClientFactory {
        private WebClientStub webClient = new WebClientStub();

        @Override
        public WebClient createClient(String url) {
            webClient.url = url;
            return webClient;
        }

        private void addJsonResponse(Map responseMap) {
            webClient.addJsonResponse(responseMap);
        }


        private void setCookieResponseHeader(String setCookieHeader) {
            webClient.setCookieHeader = setCookieHeader;
        }


        private String getSentQuery() {
            return webClient.jsonQuery;
        }

        private String getSentHeader(String key) {
            return webClient.getHeader(key);
        }

        private String getClientUrl() {
            return webClient.url;
        }

        private void reportNotAuthorized() {
            webClient.reportNotAuthorized();
        }

        @SuppressWarnings("SameParameterValue")
        private void reportAuthenticationRequired(String basicRealmName) {
            webClient.reportAuthenticationRequired(basicRealmName);
        }
    }

    static class WebClientStub implements WebClient {

        private String url;
        private String username;
        private String password;
        private String jsonQuery;
        private int status = SUCCESS;
        private String basicRealmName;
        private String setCookieHeader;
        private List<String> responseList = new ArrayList<>();
        private Iterator<String> responses;
        private Map<String,String> headers = new HashMap<>();

        private void addJsonResponse(Map responseMap) {
            responseList.add(new Gson().toJson(responseMap));
        }

        void reportNotAuthorized() {
            status = NOT_AUTHORIZED;
        }

        @SuppressWarnings("SameParameterValue")
        void reportAuthenticationRequired(String basicRealmName) {
            this.basicRealmName = basicRealmName;
        }

        String getHeader(String key) {
            return headers.get(key);
        }

        @Override
        public void putHeader(String key, String value) {
            headers.put(key, value);
        }

        @Override
        public String doQuery(String jsonQuery) throws IOException {
            if (status == NOT_AUTHORIZED) throw new NotAuthorizedException();
            if (basicRealmName != null) throw new BasicAuthenticationChallengeException(basicRealmName);
            
            this.jsonQuery = jsonQuery;
            return nextJsonResponse();
        }

        @Override
        public String getSetCookieHeader() {
            return setCookieHeader;
        }

        private String nextJsonResponse() {
            if (responses == null)
                responses = responseList.iterator();

            return responses.hasNext() ? responses.next() : null;
        }
    }


    abstract static class ServletInputStreamStub extends ServletInputStream {
        private InputStream inputStream;

        public ServletInputStreamStub(String contents) {
            inputStream = new ByteArrayInputStream(contents.getBytes());
        }

        @Override
        public int read() throws IOException {
            return inputStream.read();
        }
    }

}

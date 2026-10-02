package com.agent.platform.procurement.erp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static com.agent.platform.procurement.erp.ProcurementErpException.Code.*;

/** Frappe REST transport, used only by the ERP adapter, never by MCP tool handlers. */
final class ErpNextClient {
    private final String base, authorization;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public ErpNextClient(String base, String apiKey, String apiSecret, Duration timeout) {
        URI uri = URI.create(base);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank() ||
                timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("invalid ERP configuration");
        this.base = base.replaceAll("/+$", "");
        this.authorization = "token " + apiKey + ":" + apiSecret;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static ErpNextClient fromEnvironment() {
        return new ErpNextClient(env("ERPNEXT_URL"), env("ERPNEXT_API_KEY"), env("ERPNEXT_API_SECRET"), Duration.ofSeconds(15));
    }
    static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing environment variable " + key);
        return value;
    }
    public Optional<JsonNode> get(String type, String id) {
        try { return Optional.of(request("GET", resource(type) + "/" + enc(id), null)); }
        catch (ProcurementErpException ex) { if (ex.code() == ERP_NOT_FOUND) return Optional.empty(); throw ex; }
    }
    public List<JsonNode> list(String type, List<List<Object>> filters, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
        String query = "?fields=" + enc("[\"*\"]") + "&filters=" + enc(json.writeValueAsString(filters)) +
                "&order_by=" + enc("name asc") + "&limit_start=" + offset + "&limit_page_length=" + limit;
        JsonNode data = request("GET", resource(type) + query, null);
        if (!data.isArray() || data.size() > limit) throw new ProcurementErpException(ERP_RESPONSE_INVALID);
        return java.util.stream.StreamSupport.stream(data.spliterator(), false).toList();
    }
    public JsonNode create(String type, Map<String, Object> document) {
        return request("POST", resource(type), document);
    }
    private String resource(String type) { return "/api/resource/" + enc(type); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private JsonNode request(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Authorization", authorization).header("Accept", "application/json");
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new ProcurementErpException(ERP_AUTH_ERROR);
            if (status == 404) throw new ProcurementErpException(ERP_NOT_FOUND);
            if (status == 409) throw new ProcurementErpException(ERP_CONFLICT);
            if (status >= 400) {
                // Frappe unique-field conflicts can be HTTP 417; duplicate names can be HTTP 500.
                try { if (List.of("DuplicateEntryError","UniqueValidationError").contains(json.readTree(response.body()).path("exc_type").asText()))
                    throw new ProcurementErpException(ERP_CONFLICT); }
                catch (ProcurementErpException ex) { throw ex; } catch (RuntimeException ignored) { }
            }
            if (status == 400 || status == 417 || status == 422) throw new ProcurementErpException(ERP_VALIDATION_ERROR);
            if (status >= 500) throw new ProcurementErpException(ERP_UNAVAILABLE);
            if (status < 200 || status >= 300) throw new ProcurementErpException(ERP_UNAVAILABLE);
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.hasNonNull("data") || (!root.get("data").isObject() && !root.get("data").isArray()))
                throw new ProcurementErpException(ERP_RESPONSE_INVALID);
            return root.get("data");
        } catch (ProcurementErpException ex) { throw ex; }
        catch (HttpTimeoutException ex) { throw new ProcurementErpException(ERP_TIMEOUT); }
        catch (java.io.IOException ex) { throw new ProcurementErpException(ERP_UNAVAILABLE); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ProcurementErpException(ERP_UNAVAILABLE); }
        catch (RuntimeException ex) { throw new ProcurementErpException(ERP_RESPONSE_INVALID); }
    }
}

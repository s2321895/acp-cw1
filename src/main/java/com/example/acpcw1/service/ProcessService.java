package com.example.acpcw1.service;

import com.example.acpcw1.api.NotFoundException;
import com.example.acpcw1.config.AcpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class ProcessService {

    private final ObjectMapper objectMapper;
    private final AcpProperties props;
    private final HttpClient httpClient;

    public ProcessService(ObjectMapper objectMapper, AcpProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newHttpClient();
    }

    public ArrayNode fetchAndProcess(String urlPath) {
        String url = resolveUrl(urlPath);
        String body = doGet(url);
        JsonNode root = parse(body);

        ArrayNode result = objectMapper.createArrayNode();
        if (root.isArray()) {
            for (JsonNode item : root) {
                if (item.isObject()) {
                    result.add(enrich((ObjectNode) item.deepCopy()));
                }
            }
        } else if (root.isObject()) {
            result.add(enrich((ObjectNode) root.deepCopy()));
        } else {
            throw new NotFoundException("Unsupported JSON payload");
        }

        return result;
    }

    private ObjectNode enrich(ObjectNode node) {
        double costPerMove = extractCost(node, "costPerMove");
        double costInitial = extractCost(node, "costInitial");
        double costFinal = extractCost(node, "costFinal");

        double costPer100Moves = costInitial + costFinal + (costPerMove * 100.0d);
        node.put("costPer100Moves", costPer100Moves);
        return node;
    }

    private double extractCost(ObjectNode node, String fieldName) {
        JsonNode capability = node.get("capability");
        if (capability != null && capability.isObject()) {
            JsonNode nested = capability.get(fieldName);
            if (nested != null && !nested.isNull()) {
                return safeNumber(nested);
            }
        }
        return safeNumber(node.get(fieldName));
    }

    private double safeNumber(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0d;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else {
            try {
                value = Double.parseDouble(node.asText());
            } catch (Exception e) {
                return 0d;
            }
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0d;
        }
        return value;
    }

    private String resolveUrl(String urlPath) {
        if (urlPath == null || urlPath.isBlank()) {
            throw new NotFoundException("urlPath missing");
        }
        String trimmedPath = urlPath.trim();
        if (trimmedPath.startsWith("http://") || trimmedPath.startsWith("https://")) {
            return trimmedPath;
        }

        String base = props.getUrlEndpoint();
        if (base == null || base.isBlank()) {
            throw new NotFoundException("ACP_URL_ENDPOINT missing");
        }

        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = trimmedPath.startsWith("/") ? trimmedPath : "/" + trimmedPath;
        return normalizedBase + normalizedPath;
    }

    private String doGet(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new NotFoundException("Remote data not found");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotFoundException("Remote call failed");
        } catch (IOException e) {
            throw new NotFoundException("Remote call failed");
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new NotFoundException("Remote body is not JSON");
        }
    }
}

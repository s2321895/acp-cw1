package com.example.acpcw1.service;

import com.example.acpcw1.config.AcpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fetchAndProcess_supportsAbsoluteUrl_andCalculatesCost() throws Exception {
        HttpServer server = newJsonServer("[{\"name\":\"Drone1\",\"capability\":{\"costPerMove\":0.01,\"costInitial\":4.3,\"costFinal\":6.5}}]");
        try {
            int port = server.getAddress().getPort();
            ProcessService service = new ProcessService(objectMapper, props("http://unused.local"));

            var result = service.fetchAndProcess("http://localhost:" + port + "/drones");

            assertEquals(1, result.size());
            assertEquals(11.8d, result.get(0).get("costPer100Moves").asDouble(), 0.00001d);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchAndProcess_supportsRelativePathThroughConfiguredEndpoint() throws Exception {
        HttpServer server = newJsonServer("[{\"name\":\"Drone2\",\"costPerMove\":0.02,\"costInitial\":1.0,\"costFinal\":2.0}]");
        try {
            int port = server.getAddress().getPort();
            ProcessService service = new ProcessService(objectMapper, props("http://localhost:" + port));

            var result = service.fetchAndProcess("/drones");

            assertEquals(1, result.size());
            assertEquals(5.0d, result.get(0).get("costPer100Moves").asDouble(), 0.00001d);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchAndProcess_treatsNanAsZero() throws Exception {
        HttpServer server = newJsonServer("[{\"name\":\"Drone3\",\"costPerMove\":\"NaN\",\"costInitial\":1.0,\"costFinal\":2.0}]");
        try {
            int port = server.getAddress().getPort();
            ProcessService service = new ProcessService(objectMapper, props("http://localhost:" + port));

            var result = service.fetchAndProcess("/drones");

            assertEquals(1, result.size());
            assertEquals(3.0d, result.get(0).get("costPer100Moves").asDouble(), 0.00001d);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer newJsonServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/drones", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private AcpProperties props(String endpoint) {
        AcpProperties p = new AcpProperties();
        p.setUrlEndpoint(endpoint);
        return p;
    }
}

package com.example.acpcw1.service;

import com.example.acpcw1.api.NotFoundException;
import com.example.acpcw1.config.AcpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AcpDataService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern CURRENT_SCHEMA_PATTERN = Pattern.compile("(?i)(?:^|&)currentschema=([^&]+)");

    private final S3Client s3Client;
    private final DynamoDbClient dynamoDbClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AcpProperties props;

    public AcpDataService(
            S3Client s3Client,
            DynamoDbClient dynamoDbClient,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AcpProperties props
    ) {
        this.s3Client = s3Client;
        this.dynamoDbClient = dynamoDbClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public ArrayNode getAllS3(String bucket) {
        ArrayNode result = objectMapper.createArrayNode();
        var response = s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
        for (var object : response.contents()) {
            JsonNode item = getSingleS3(bucket, object.key());
            result.add(item);
        }
        return result;
    }

    public JsonNode getSingleS3(String bucket, String key) {
        try {
            byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build())
                    .asByteArray();
            return parseJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("S3 object not found");
        } catch (Exception e) {
            throw new NotFoundException("S3 read failed");
        }
    }

    public ArrayNode getAllDynamo(String table) {
        ArrayNode result = objectMapper.createArrayNode();
        var pages = dynamoDbClient.scanPaginator(ScanRequest.builder().tableName(table).build());
        pages.forEach(page -> page.items().forEach(item -> result.add(attributeMapToJson(item))));
        return result;
    }

    public JsonNode getSingleDynamo(String table, String key) {
        JsonNode byId = tryGetByDynamoKey(table, "id", key);
        if (byId != null) {
            return byId;
        }

        JsonNode byName = tryGetByDynamoKey(table, "name", key);
        if (byName != null) {
            return byName;
        }

        try {
            var pages = dynamoDbClient.scanPaginator(ScanRequest.builder().tableName(table).build());
            pages.forEach(page -> {
                for (Map<String, AttributeValue> item : page.items()) {
                    if (itemContainsKeyValue(item, key)) {
                        throw new FoundItemException(attributeMapToJson(item));
                    }
                }
            });
        } catch (FoundItemException found) {
            return found.item;
        }

        throw new NotFoundException("Dynamo item not found");
    }

    public List<Map<String, Object>> getAllPostgres(String table) {
        String safeTable = quoteIdentifier(table);
        try {
            return jdbcTemplate.queryForList("SELECT * FROM " + safeTable);
        } catch (BadSqlGrammarException e) {
            throw new NotFoundException("Postgres table not found");
        }
    }

    public void putAllToDynamoByName(ArrayNode data) {
        String table = resolveSid();
        for (JsonNode node : data) {
            if (!node.isObject() || node.get("name") == null) {
                continue;
            }
            String name = node.get("name").asText();
            Map<String, AttributeValue> item = jsonToAttributeMap((ObjectNode) node);
            item.put("id", AttributeValue.builder().s(name).build());
            item.put("name", AttributeValue.builder().s(name).build());
            dynamoDbClient.putItem(PutItemRequest.builder().tableName(table).item(item).build());
        }
    }

    public void putAllToS3ByName(ArrayNode data) {
        String bucket = resolveSid();
        for (JsonNode node : data) {
            if (!node.isObject() || node.get("name") == null) {
                continue;
            }
            String key = node.get("name").asText();
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromString(node.toString(), StandardCharsets.UTF_8)
            );
        }
    }

    public void insertAllToPostgres(String table, ArrayNode data) {
        String safeTable = quoteIdentifier(table);
        Map<String, String> dbColumnsByLower = getTableColumnsByLowerName(table);
        for (JsonNode node : data) {
            if (!node.isObject()) {
                continue;
            }

            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();

            node.fields().forEachRemaining(entry -> {
                String columnName = resolveColumnName(entry.getKey(), dbColumnsByLower);
                if (columnName != null) {
                    columns.add(quoteIdentifier(columnName));
                    values.add(jsonToSqlValue(entry.getValue()));
                }
            });

            if (columns.isEmpty()) {
                continue;
            }

            String sql = "INSERT INTO " + safeTable + " (" + String.join(",", columns) + ") VALUES (" +
                    "?,".repeat(columns.size()).replaceAll(",$", "") + ") ON CONFLICT DO NOTHING";

            jdbcTemplate.update(sql, values.toArray());
        }
    }

    public void copyPostgresToDynamo(String postgresTable) {
        List<Map<String, Object>> rows = getAllPostgres(postgresTable);
        String table = resolveSid();
        for (Map<String, Object> row : rows) {
            String uuid = UUID.randomUUID().toString();
            Map<String, AttributeValue> item = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                item.put(entry.getKey(), objectToAttributeValue(entry.getValue()));
            }
            item.put("id", AttributeValue.builder().s(uuid).build());
            item.put("name", AttributeValue.builder().s(uuid).build());
            dynamoDbClient.putItem(PutItemRequest.builder().tableName(table).item(item).build());
        }
    }

    public void copyPostgresToS3(String postgresTable) {
        List<Map<String, Object>> rows = getAllPostgres(postgresTable);
        String bucket = resolveSid();
        for (Map<String, Object> row : rows) {
            String key = UUID.randomUUID().toString();
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromString(objectMapper.valueToTree(row).toString(), StandardCharsets.UTF_8)
            );
        }
    }

    private JsonNode parseJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new NotFoundException("Invalid JSON payload");
        }
    }

    private JsonNode attributeMapToJson(Map<String, AttributeValue> map) {
        ObjectNode objectNode = objectMapper.createObjectNode();
        for (Map.Entry<String, AttributeValue> entry : map.entrySet()) {
            objectNode.set(entry.getKey(), attributeValueToJson(entry.getValue()));
        }
        return objectNode;
    }

    private JsonNode attributeValueToJson(AttributeValue value) {
        if (value.s() != null) {
            return objectMapper.getNodeFactory().textNode(value.s());
        }
        if (value.n() != null) {
            try {
                if (value.n().contains(".")) {
                    return objectMapper.getNodeFactory().numberNode(Double.parseDouble(value.n()));
                }
                return objectMapper.getNodeFactory().numberNode(Long.parseLong(value.n()));
            } catch (NumberFormatException e) {
                return objectMapper.getNodeFactory().numberNode(0);
            }
        }
        if (value.bool() != null) {
            return objectMapper.getNodeFactory().booleanNode(value.bool());
        }
        if (Boolean.TRUE.equals(value.nul())) {
            return objectMapper.getNodeFactory().nullNode();
        }
        if (value.m() != null && !value.m().isEmpty()) {
            return attributeMapToJson(value.m());
        }
        if (value.l() != null && !value.l().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            value.l().forEach(item -> arr.add(attributeValueToJson(item)));
            return arr;
        }
        return objectMapper.getNodeFactory().nullNode();
    }

    private Map<String, AttributeValue> jsonToAttributeMap(ObjectNode objectNode) {
        Map<String, AttributeValue> map = new LinkedHashMap<>();
        objectNode.fields().forEachRemaining(entry -> map.put(entry.getKey(), jsonToAttributeValue(entry.getValue())));
        return map;
    }

    private AttributeValue jsonToAttributeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return AttributeValue.builder().nul(true).build();
        }
        if (node.isTextual()) {
            return AttributeValue.builder().s(node.asText()).build();
        }
        if (node.isNumber()) {
            return AttributeValue.builder().n(node.numberValue().toString()).build();
        }
        if (node.isBoolean()) {
            return AttributeValue.builder().bool(node.asBoolean()).build();
        }
        if (node.isArray()) {
            List<AttributeValue> list = new ArrayList<>();
            node.forEach(item -> list.add(jsonToAttributeValue(item)));
            return AttributeValue.builder().l(list).build();
        }
        if (node.isObject()) {
            return AttributeValue.builder().m(jsonToAttributeMap((ObjectNode) node)).build();
        }
        return AttributeValue.builder().s(node.asText()).build();
    }

    private AttributeValue objectToAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            return AttributeValue.builder().n(number.toString()).build();
        }
        if (value instanceof Boolean) {
            Boolean bool = (Boolean) value;
            return AttributeValue.builder().bool(bool).build();
        }
        return AttributeValue.builder().s(String.valueOf(value)).build();
    }

    private Object jsonToSqlValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if ("NaN".equalsIgnoreCase(text) || "Infinity".equalsIgnoreCase(text) || "-Infinity".equalsIgnoreCase(text)) {
                return 0d;
            }
            return text;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isObject() || node.isArray()) {
            return toJsonbObject(node.toString());
        }
        return node.toString();
    }

    private PGobject toJsonbObject(String json) {
        try {
            PGobject value = new PGobject();
            value.setType("jsonb");
            value.setValue(json);
            return value;
        } catch (Exception e) {
            throw new NotFoundException("Invalid JSON value for SQL");
        }
    }

    private JsonNode tryGetByDynamoKey(String table, String keyName, String keyValue) {
        try {
            Map<String, AttributeValue> key = Map.of(keyName, AttributeValue.builder().s(keyValue).build());
            var response = dynamoDbClient.getItem(GetItemRequest.builder().tableName(table).key(key).build());
            if (response.hasItem() && !response.item().isEmpty()) {
                return attributeMapToJson(response.item());
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean itemContainsKeyValue(Map<String, AttributeValue> item, String key) {
        for (AttributeValue value : item.values()) {
            if (value.s() != null && key.equals(value.s())) {
                return true;
            }
            if (value.n() != null && key.equals(value.n())) {
                return true;
            }
        }
        return false;
    }

    private static class FoundItemException extends RuntimeException {
        private final JsonNode item;

        private FoundItemException(JsonNode item) {
            this.item = item;
        }
    }

    private String quoteIdentifier(String input) {
        if (input == null || !SAFE_IDENTIFIER.matcher(input).matches()) {
            throw new NotFoundException("Invalid identifier");
        }
        return '"' + input + '"';
    }

    private String resolveSid() {
        if (props.getSid() != null && !props.getSid().isBlank()) {
            return props.getSid().trim();
        }

        String jdbcUrl = props.getPostgres();
        if (jdbcUrl != null) {
            int queryStart = jdbcUrl.indexOf('?');
            if (queryStart >= 0 && queryStart < jdbcUrl.length() - 1) {
                String query = jdbcUrl.substring(queryStart + 1);
                var matcher = CURRENT_SCHEMA_PATTERN.matcher(query);
                if (matcher.find()) {
                    String encoded = matcher.group(1);
                    String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                    if (!decoded.isBlank()) {
                        int comma = decoded.indexOf(',');
                        return comma > 0 ? decoded.substring(0, comma) : decoded;
                    }
                }
            }
        }

        throw new NotFoundException("SID could not be resolved");
    }

    private Map<String, String> getTableColumnsByLowerName(String table) {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() " +
                        "AND lower(table_name) = lower(?)",
                String.class,
                table
        );
        if (columns.isEmpty()) {
            throw new NotFoundException("Postgres table not found");
        }

        Map<String, String> byLower = new LinkedHashMap<>();
        for (String column : columns) {
            byLower.put(column.toLowerCase(Locale.ROOT), column);
        }
        return byLower;
    }

    private String resolveColumnName(String jsonFieldName, Map<String, String> dbColumnsByLower) {
        if (jsonFieldName == null) {
            return null;
        }
        return dbColumnsByLower.get(jsonFieldName.toLowerCase(Locale.ROOT));
    }
}

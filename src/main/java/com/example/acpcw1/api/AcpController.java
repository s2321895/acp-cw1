package com.example.acpcw1.api;

import com.example.acpcw1.dto.UrlPathRequest;
import com.example.acpcw1.service.AcpDataService;
import com.example.acpcw1.service.ProcessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import javax.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/acp")
public class AcpController {

    private final AcpDataService dataService;
    private final ProcessService processService;

    public AcpController(AcpDataService dataService, ProcessService processService) {
        this.dataService = dataService;
        this.processService = processService;
    }

    @GetMapping("/all/s3/{bucket}")
    public ResponseEntity<ArrayNode> getAllS3(@PathVariable String bucket) {
        return ResponseEntity.ok(dataService.getAllS3(bucket));
    }

    @GetMapping("/single/s3/{bucket}/{key}")
    public ResponseEntity<JsonNode> getSingleS3(@PathVariable String bucket, @PathVariable String key) {
        return ResponseEntity.ok(dataService.getSingleS3(bucket, key));
    }

    @GetMapping("/all/dynamo/{table}")
    public ResponseEntity<ArrayNode> getAllDynamo(@PathVariable String table) {
        return ResponseEntity.ok(dataService.getAllDynamo(table));
    }

    @GetMapping("/single/dynamo/{table}/{key}")
    public ResponseEntity<JsonNode> getSingleDynamo(@PathVariable String table, @PathVariable String key) {
        return ResponseEntity.ok(dataService.getSingleDynamo(table, key));
    }

    @GetMapping("/all/postgres/{table}")
    public ResponseEntity<List<Map<String, Object>>> getAllPostgres(@PathVariable String table) {
        return ResponseEntity.ok(dataService.getAllPostgres(table));
    }

    @PostMapping("/process/dump")
    public ResponseEntity<ArrayNode> processDump(@Valid @RequestBody UrlPathRequest request) {
        return ResponseEntity.ok(processService.fetchAndProcess(request.getUrlPath()));
    }

    @PostMapping("/process/dynamo")
    public ResponseEntity<Void> processDynamo(@Valid @RequestBody UrlPathRequest request) {
        ArrayNode processed = processService.fetchAndProcess(request.getUrlPath());
        dataService.putAllToDynamoByName(processed);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/process/s3")
    public ResponseEntity<Void> processS3(@Valid @RequestBody UrlPathRequest request) {
        ArrayNode processed = processService.fetchAndProcess(request.getUrlPath());
        dataService.putAllToS3ByName(processed);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/process/postgres/{table}")
    public ResponseEntity<Void> processPostgres(@PathVariable String table, @Valid @RequestBody UrlPathRequest request) {
        ArrayNode processed = processService.fetchAndProcess(request.getUrlPath());
        dataService.insertAllToPostgres(table, processed);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/copy-content/dynamo/{table}")
    public ResponseEntity<Void> copyToDynamo(@PathVariable String table) {
        dataService.copyPostgresToDynamo(table);
        return ResponseEntity.ok().build();
    }

    @PostMapping({"/copy-content/s3/{table}", "/copy-content/S3/{table}"})
    public ResponseEntity<Void> copyToS3(@PathVariable String table) {
        dataService.copyPostgresToS3(table);
        return ResponseEntity.ok().build();
    }
}

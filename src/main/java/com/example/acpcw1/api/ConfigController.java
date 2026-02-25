package com.example.acpcw1.api;

import com.example.acpcw1.config.AcpProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/acp")
public class ConfigController {

    private final AcpProperties props;

    public ConfigController(AcpProperties props) {
        this.props = props;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "postgresSet", props.getPostgres() != null && !props.getPostgres().isBlank(),
                "s3Set", props.getS3() != null && !props.getS3().isBlank(),
                "dynamodbSet", props.getDynamodb() != null && !props.getDynamodb().isBlank()
        );
    }
}

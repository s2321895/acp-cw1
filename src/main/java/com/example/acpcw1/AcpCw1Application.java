package com.example.acpcw1;

import com.example.acpcw1.config.AcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@EnableConfigurationProperties(AcpProperties.class)
public class AcpCw1Application {

    public static void main(String[] args) {
        SpringApplication.run(AcpCw1Application.class, args);
    }
}


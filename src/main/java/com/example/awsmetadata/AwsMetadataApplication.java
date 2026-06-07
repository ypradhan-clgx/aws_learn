package com.example.awsmetadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AwsMetadataApplication {

    public static void main(String[] args) {
        SpringApplication.run(AwsMetadataApplication.class, args);
    }
}

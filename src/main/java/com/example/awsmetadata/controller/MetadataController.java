package com.example.awsmetadata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

@RestController
public class MetadataController {

    @GetMapping("/")
    public Map<String, String> getMetadata() {
        Map<String, String> result = new HashMap<>();
        try {
            String az = fetchMetadata("placement/availability-zone");
            if (az == null || az.isEmpty()) {
                result.put("error", "Could not retrieve availability zone");
                return result;
            }
            String region = az.substring(0, az.length() - 1);
            result.put("region", region);
            result.put("availabilityZone", az);
        } catch (Exception e) {
            result.put("error", e.toString());
        }
        return result;
    }

    private String fetchMetadata(String path) throws IOException {
        // Step 1: Get the token
        URL tokenUrl = new URL("http://169.254.169.254/latest/api/token");
        HttpURLConnection tokenConn = (HttpURLConnection) tokenUrl.openConnection();
        tokenConn.setRequestMethod("PUT");
        tokenConn.setRequestProperty("X-aws-ec2-metadata-token-ttl-seconds", "21600");
        tokenConn.setDoOutput(true);
        String token;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(tokenConn.getInputStream()))) {
            token = in.readLine();
        }

        // Step 2: Use the token to get metadata
        URL metaUrl = new URL("http://169.254.169.254/latest/meta-data/" + path);
        HttpURLConnection metaConn = (HttpURLConnection) metaUrl.openConnection();
        metaConn.setRequestMethod("GET");
        metaConn.setRequestProperty("X-aws-ec2-metadata-token", token);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(metaConn.getInputStream()))) {
            return in.readLine();
        }
    }
}
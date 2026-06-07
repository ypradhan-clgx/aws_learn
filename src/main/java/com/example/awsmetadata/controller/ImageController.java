package com.example.awsmetadata.controller;

import com.example.awsmetadata.model.Image;
import com.example.awsmetadata.model.ImageStats;
import com.example.awsmetadata.service.DynamoDbService;
import com.example.awsmetadata.service.ImageService;
import com.example.awsmetadata.service.SnsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class ImageController {

    private final ImageService imageService;
    private final SnsService snsService;
    private final DynamoDbService dynamoDbService;

    public ImageController(ImageService imageService, SnsService snsService,
                           DynamoDbService dynamoDbService) {
        this.imageService = imageService;
        this.snsService = snsService;
        this.dynamoDbService = dynamoDbService;
    }

    /**
     * GET /health
     * Simple health-check endpoint for ALB target-group checks.
     * Always returns HTTP 200 with an empty body.
     */
    @GetMapping("/health")
    public ResponseEntity<Void> health() {
        return ResponseEntity.ok().build();
    }

    /**
     * GET /v1/images
     * Returns all image metadata objects as a JSON array.
     *
     * GET /v1/images?name={imageName}
     * Returns a single image metadata object for the given name,
     * or 404 if not found.
     */
    @GetMapping("/v1/images")
    public ResponseEntity<?> getImages(@RequestParam(required = false) String name) {
        if (name != null && !name.isBlank()) {
            Optional<Image> found = imageService.getImageByName(name);
            return found.<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
        List<Image> images = imageService.getAllImages();
        return ResponseEntity.ok(images);
    }

    /**
     * GET /v1/images/download?name={imageName}
     * Downloads the image file for the given name.
     * Returns 404 if the name does not exist.
     */
    @GetMapping("/v1/images/download")
    public ResponseEntity<byte[]> downloadImage(@RequestParam String name) {
        Optional<Image> imageOpt = imageService.getImageByName(name);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Image image = imageOpt.get();
        try {
            byte[] content = imageService.downloadImage(name);
            String ext = image.getFileExtension();
            String filename = image.getName() + (ext == null || ext.isBlank() ? "" : "." + ext);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * DELETE /v1/images/{id}
     * Deletes the image file from S3 and its metadata row from RDS.
     * Returns 204 on success, 404 if not found.
     */
    @DeleteMapping("/v1/images/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable String id) {
        String cleanId = id.replaceAll("^\"|\"$", "");
        try {
            imageService.deleteImage(cleanId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /v1/images/{id}
     * Returns the image metadata for the given ID and records a view.
     * Returns 404 if not found.
     * Strips surrounding double-quotes from {id} to handle clients that pass
     * JSON-encoded UUIDs (e.g. jq without -r).
     */
    @GetMapping("/v1/images/{id}")
    public ResponseEntity<Image> getImageById(@PathVariable String id) {
        String cleanId = id.replaceAll("^\"|\"$", "");
        Optional<Image> found = imageService.getImageById(cleanId);
        return found.<ResponseEntity<Image>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /v1/images/{id}/download
     * Downloads the image file for the given ID and records a download.
     * Returns 404 if not found.
     * Strips surrounding double-quotes from {id}.
     */
    @GetMapping("/v1/images/{id}/download")
    public ResponseEntity<byte[]> downloadImageById(@PathVariable String id) {
        String cleanId = id.replaceAll("^\"|\"$", "");
        Optional<Image> imageOpt = imageService.findById(cleanId);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Image image = imageOpt.get();
        try {
            byte[] content = imageService.downloadImageById(cleanId);
            String ext = image.getFileExtension();
            String filename = image.getName() + (ext == null || ext.isBlank() ? "" : "." + ext);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /v1/images
     * Uploads a new image.
     * Multipart form fields:
     *   name  – String  – logical name for the image
     *   image – File    – the image binary
     * Returns 201 Created with the persisted Image metadata (including generated id).
     */
    @PostMapping(value = "/v1/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Image> uploadImage(
            @RequestParam("name") String name,
            @RequestParam("image") MultipartFile file) {
        try {
            Image saved = imageService.uploadImage(name, file);
            return ResponseEntity.status(201).body(saved);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /v1/subscribe/{email}
     * Subscribes the given email address to the SNS topic.
     * The subscriber will receive a confirmation email from AWS SNS.
     */
    @PostMapping("/v1/subscribe/{email:.+}")
    public ResponseEntity<Map<String, String>> subscribe(@PathVariable String email) {
        try {
            String subscriptionArn = snsService.subscribeEmail(email);
            return ResponseEntity.ok(Map.of(
                    "message", "Subscription request sent.",
                    "subscriptionArn", subscriptionArn
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /v1/unsubscribe/{email}
     * Unsubscribes the given email address from the SNS topic.
     */
    @PostMapping("/v1/unsubscribe/{email:.+}")
    public ResponseEntity<Map<String, String>> unsubscribe(@PathVariable String email) {
        try {
            snsService.unsubscribeEmail(email);
            return ResponseEntity.ok(Map.of(
                    "message", "Unsubscribed " + email + " from notifications successfully."
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /v1/images/{id}/stats
     * Returns the current view_count, download_count, last_viewed, and
     * last_downloaded for the image with the given ID.
     * Returns 404 if no image with that ID exists.
     */
    @GetMapping("/v1/images/{id}/stats")
    public ResponseEntity<ImageStats> getImageStats(@PathVariable String id) {
        // Strip surrounding double-quotes that some clients include in the path
        // e.g. /"uuid"/stats -> "uuid" -> uuid
        String cleanId = id.replaceAll("^\"|\"$", "");
        if (imageService.findById(cleanId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageStats stats = dynamoDbService.getStats(cleanId);
        return ResponseEntity.ok(stats);
    }
}


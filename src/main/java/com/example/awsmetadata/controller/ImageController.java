package com.example.awsmetadata.controller;

import com.example.awsmetadata.model.Image;
import com.example.awsmetadata.service.ImageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@RestController
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
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
        try {
            imageService.deleteImage(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
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
}


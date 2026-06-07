package com.example.awsmetadata.service;

import com.example.awsmetadata.model.Image;
import com.example.awsmetadata.repository.ImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final ImageRepository imageRepository;
    private final S3Service s3Service;
    private final SqsService sqsService;
    private final DynamoDbService dynamoDbService;

    public ImageService(ImageRepository imageRepository, S3Service s3Service,
                        SqsService sqsService, DynamoDbService dynamoDbService) {
        this.imageRepository = imageRepository;
        this.s3Service = s3Service;
        this.sqsService = sqsService;
        this.dynamoDbService = dynamoDbService;
    }

    /** Returns all image metadata records from RDS and records a view for each. */
    public List<Image> getAllImages() {
        List<Image> images = imageRepository.findAll();
        images.forEach(img -> {
            try {
                dynamoDbService.recordView(img.getId());
            } catch (Exception e) {
                log.error("Failed to record view for image {}: {}", img.getId(), e.getMessage());
            }
        });
        return images;
    }

    /** Returns a single image record looked up by name and records a view if found. */
    public Optional<Image> getImageByName(String name) {
        Optional<Image> result = imageRepository.findByName(name);
        result.ifPresent(img -> {
            try {
                dynamoDbService.recordView(img.getId());
            } catch (Exception e) {
                log.error("Failed to record view for image {}: {}", img.getId(), e.getMessage());
            }
        });
        return result;
    }

    /** Finds an image by its ID (no view recording – used for existence checks). */
    public Optional<Image> findById(String id) {
        return imageRepository.findById(id);
    }

    /**
     * Returns a single image record looked up by ID and records a view if found.
     * Used by GET /v1/images/{id}.
     */
    public Optional<Image> getImageById(String id) {
        Optional<Image> result = imageRepository.findById(id);
        result.ifPresent(img -> {
            try {
                dynamoDbService.recordView(img.getId());
            } catch (Exception e) {
                log.error("Failed to record view for image {}: {}", img.getId(), e.getMessage());
            }
        });
        return result;
    }

    /**
     * Downloads the raw file bytes from S3 for a given image ID and
     * atomically increments the download counter in DynamoDB.
     * Used by GET /v1/images/{id}/download.
     */
    public byte[] downloadImageById(String id) {
        Image image = imageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Image not found with id: " + id));
        try {
            dynamoDbService.recordDownload(image.getId());
        } catch (Exception e) {
            log.error("Failed to record download for image {}: {}", image.getId(), e.getMessage());
        }
        return s3Service.downloadFile(buildS3Key(image));
    }

    /**
     * Saves metadata to RDS and uploads the file bytes to S3.
     * S3 key pattern: images/{uuid}.{extension}
     */
    public Image uploadImage(String name, MultipartFile file) throws IOException {
        String extension = extractExtension(file.getOriginalFilename());

        Image image = new Image();
        image.setName(name);
        image.setSize(file.getSize());
        image.setFileExtension(extension);
        image.setLastUpdated(LocalDateTime.now());

        // Persist first so the UUID id is generated
        image = imageRepository.save(image);

        // Upload content to S3
        s3Service.uploadFile(buildS3Key(image), file.getBytes(), file.getContentType());

        // Publish image metadata to SQS for async SNS notification
        try {
            sqsService.sendImageUploadMessage(image);
        } catch (Exception e) {
            log.error("Failed to send SQS upload message for image {}: {}", image.getId(), e.getMessage());
        }

        return image;
    }

    /**
     * Downloads the raw file bytes from S3 for a given image name
     * and atomically increments the download counter in DynamoDB.
     */
    public byte[] downloadImage(String name) {
        Image image = imageRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("Image not found: " + name));
        // Record the download before fetching bytes so the counter is always incremented
        try {
            dynamoDbService.recordDownload(image.getId());
        } catch (Exception e) {
            log.error("Failed to record download for image {}: {}", image.getId(), e.getMessage());
        }
        return s3Service.downloadFile(buildS3Key(image));
    }

    /**
     * Deletes the image file from S3 and removes the metadata row from RDS.
     */
    public void deleteImage(String id) {
        Image image = imageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Image not found with id: " + id));
        s3Service.deleteFile(buildS3Key(image));
        imageRepository.deleteById(id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildS3Key(Image image) {
        String ext = image.getFileExtension();
        return "images/" + image.getId() + (ext == null || ext.isBlank() ? "" : "." + ext);
    }

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.') + 1);
        }
        return "";
    }
}


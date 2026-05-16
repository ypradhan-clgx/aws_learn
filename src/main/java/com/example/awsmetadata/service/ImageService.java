package com.example.awsmetadata.service;

import com.example.awsmetadata.model.Image;
import com.example.awsmetadata.repository.ImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ImageService {

    private final ImageRepository imageRepository;
    private final S3Service s3Service;
    private final SqsService sqsService;

    public ImageService(ImageRepository imageRepository, S3Service s3Service, SqsService sqsService) {
        this.imageRepository = imageRepository;
        this.s3Service = s3Service;
        this.sqsService = sqsService;
    }

    /** Returns all image metadata records from RDS. */
    public List<Image> getAllImages() {
        return imageRepository.findAll();
    }

    /** Returns a single image record looked up by name. */
    public Optional<Image> getImageByName(String name) {
        return imageRepository.findByName(name);
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
        sqsService.sendImageUploadMessage(image);

        return image;
    }

    /** Downloads the raw file bytes from S3 for a given image name. */
    public byte[] downloadImage(String name) {
        Image image = imageRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("Image not found: " + name));
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


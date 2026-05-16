package com.example.awsmetadata.service;

import com.example.awsmetadata.model.Image;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SqsService {

    private static final Logger log = LoggerFactory.getLogger(SqsService.class);
    private static final int MAX_MESSAGES_PER_POLL = 10;
    private static final int WAIT_TIME_SECONDS = 5;

    private final SqsClient sqsClient;
    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    @Value("${server.appBaseUrl:http://localhost:8080}")
    private String appBaseUrl;

    public SqsService(SqsClient sqsClient, SnsService snsService, ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends image metadata to the SQS queue as a JSON message.
     *
     * @param image the newly uploaded image
     */
    public void sendImageUploadMessage(Image image) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", image.getId());
        payload.put("name", image.getName());
        payload.put("size", image.getSize());
        payload.put("fileExtension", image.getFileExtension());

        try {
            String body = objectMapper.writeValueAsString(payload);
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build();

            SendMessageResponse response = sqsClient.sendMessage(request);
            log.info("Sent SQS message for image '{}'. MessageId: {}", image.getName(), response.messageId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SQS message for image '{}'", image.getName(), e);
            throw new RuntimeException("Failed to send SQS message", e);
        }
    }

    /**
     * Background consumer: polls the SQS queue every 5 seconds (fixed delay).
     * For each message:
     *   1. Deserializes the image metadata.
     *   2. Publishes an SNS notification.
     *   3. Deletes the message from the queue.
     */
    @Scheduled(fixedDelay = 5000)
    public void pollQueue() {
        if (queueUrl == null || queueUrl.isBlank()) {
            return; // Not configured – skip
        }

        try {
            ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
                    .waitTimeSeconds(WAIT_TIME_SECONDS)
                    .build();

            List<Message> messages = sqsClient.receiveMessage(request).messages();

            if (!messages.isEmpty()) {
                log.info("Received {} message(s) from SQS queue.", messages.size());
            }

            for (Message message : messages) {
                try {
                    processMessage(message);
                    deleteMessage(message);
                } catch (Exception e) {
                    log.error("Error processing SQS message {}: {}", message.messageId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error polling SQS queue: {}", e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void processMessage(Message message) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(message.body(), Map.class);

        Image image = new Image();
        image.setId((String) payload.get("id"));
        image.setName((String) payload.get("name"));
        image.setSize(((Number) payload.get("size")).longValue());
        image.setFileExtension((String) payload.get("fileExtension"));

        log.info("Processing SQS message for image '{}'", image.getName());
        snsService.publishImageUploadNotification(image, appBaseUrl);
    }

    private void deleteMessage(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();
        sqsClient.deleteMessage(deleteRequest);
        log.info("Deleted SQS message {}", message.messageId());
    }
}


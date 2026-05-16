package com.example.awsmetadata.service;

import com.example.awsmetadata.model.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.util.List;

@Service
public class SnsService {

    private static final Logger log = LoggerFactory.getLogger(SnsService.class);

    private final SnsClient snsClient;

    @Value("${aws.sns.topic-arn}")
    private String topicArn;

    public SnsService(SnsClient snsClient) {
        this.snsClient = snsClient;
    }

    /**
     * Subscribes the given email address to the SNS topic.
     * The subscriber will receive a confirmation email from AWS SNS.
     *
     * @return the subscription ARN (will be "PendingConfirmation" until confirmed)
     */
    public String subscribeEmail(String email) {
        SubscribeRequest request = SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("email")
                .endpoint(email)
                .returnSubscriptionArn(true)
                .build();

        SubscribeResponse response = snsClient.subscribe(request);
        log.info("Subscribed {} to SNS topic. Subscription ARN: {}", email, response.subscriptionArn());
        return response.subscriptionArn();
    }

    /**
     * Finds the subscription ARN for the given email and unsubscribes it.
     * Iterates through topic subscriptions to match by endpoint.
     * Also handles pending-confirmation subscriptions (ARN = "PendingConfirmation").
     */
    public void unsubscribeEmail(String email) {
        String subscriptionArn = findSubscriptionArn(email);
        if (subscriptionArn == null) {
            throw new RuntimeException("No subscription found for email: " + email);
        }

        // PendingConfirmation means the user never confirmed — nothing to unsubscribe via API
        if ("PendingConfirmation".equals(subscriptionArn)) {
            log.info("Subscription for {} is pending confirmation — treating as unsubscribed.", email);
            return;
        }

        UnsubscribeRequest request = UnsubscribeRequest.builder()
                .subscriptionArn(subscriptionArn)
                .build();

        snsClient.unsubscribe(request);
        log.info("Unsubscribed {} (ARN: {}) from SNS topic.", email, subscriptionArn);
    }

    /**
     * Publishes an image-upload notification to the SNS topic in plain text.
     *
     * @param image the image that was uploaded
     * @param appBaseUrl base URL of the application (used for the delete link)
     */
    public void publishImageUploadNotification(Image image, String appBaseUrl) {
        String message = buildNotificationMessage(image, appBaseUrl);

        PublishRequest request = PublishRequest.builder()
                .topicArn(topicArn)
                .subject("New Image Uploaded")
                .message(message)
                .build();

        PublishResponse response = snsClient.publish(request);
        log.info("Published SNS notification for image '{}'. MessageId: {}", image.getName(), response.messageId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String findSubscriptionArn(String email) {
        String nextToken = null;
        do {
            ListSubscriptionsByTopicRequest.Builder reqBuilder = ListSubscriptionsByTopicRequest.builder()
                    .topicArn(topicArn);
            if (nextToken != null) {
                reqBuilder.nextToken(nextToken);
            }
            ListSubscriptionsByTopicResponse response = snsClient.listSubscriptionsByTopic(reqBuilder.build());

            List<Subscription> subscriptions = response.subscriptions();
            for (Subscription sub : subscriptions) {
                if (email.equalsIgnoreCase(sub.endpoint())) {
                    return sub.subscriptionArn(); // may be "PendingConfirmation"
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null);

        return null;
    }

    private String buildNotificationMessage(Image image, String appBaseUrl) {
        String deleteUrl = appBaseUrl + "/v1/images/" + image.getId();
        return String.format(
                "A new image has been uploaded.\n\n" +
                "Image Details:\n" +
                "  Name      : %s\n" +
                "  Size      : %d bytes\n" +
                "  Extension : %s\n\n" +
                "To delete this image, send a DELETE request to:\n" +
                "  %s\n",
                image.getName(),
                image.getSize(),
                image.getFileExtension(),
                deleteUrl
        );
    }
}


package mta.eda.consumer.exception;

import lombok.Getter;

/**
 * TopicNotFoundException
 * Thrown when a required Kafka topic does not exist and auto-creation is disabled.
 * This is a critical error that indicates infrastructure misconfiguration.
 *
 * Common causes:
 * - Topic name misconfiguration in application.properties
 * - Topic was not pre-created in Kafka
 * - Kafka broker has auto.create.topics.enable=false
 *
 * Resolution:
 * - Verify topic name configuration matches existing topics
 * - Manually create the topic using kafka-topics.sh or AdminClient
 * - Or enable Kafka's auto-topic-creation (not recommended for production)
 */
@Getter
public class TopicNotFoundException extends RuntimeException {
    private final String topicName;

    public TopicNotFoundException(String topicName, String message) {
        super(message);
        this.topicName = topicName;
    }

    public TopicNotFoundException(String topicName, String message, Throwable cause) {
        super(message, cause);
        this.topicName = topicName;
    }

    @Override
    public String toString() {
        return "TopicNotFoundException{" +
                "topicName='" + topicName + '\'' +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}


package mta.eda.consumer.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * AllOrdersFromTopicRequest - DTO for GET /getAllOrderIdsFromTopic?topic={topicName}
 * Input: topicName (string)
 */
public record AllOrdersFromTopicRequest(
    @NotBlank(message = "topicName is required")
    @JsonProperty("topicName")
    String topicName
) {}

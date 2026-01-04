package mta.eda.producer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import mta.eda.producer.model.request.CreateOrderRequest;
import mta.eda.producer.model.request.UpdateOrderRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    }
)
@EmbeddedKafka(partitions = 1, topics = { "order-events" })
@ActiveProfiles("test")
@DirtiesContext
class OrderControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp(@Autowired EmbeddedKafkaBroker embeddedKafkaBroker) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        objectMapper = new ObjectMapper();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList("order-events"));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void testCreateOrder_Success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("A100", 2);
        
        mockMvc.perform(post("/cart-service/create-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Order created successfully"));

        // Verify Kafka message
        ConsumerRecord<String, String> record = waitForRecord("ORD-A100");
        assertNotNull(record, "Should receive message from Kafka");
        assertEquals("ORD-A100", record.key());
        assertTrue(record.value().contains("ORD-A100"));
    }

    @Test
    void testUpdateOrder_Success() throws Exception {
        // Create first
        CreateOrderRequest createRequest = new CreateOrderRequest("B200", 1);
        mockMvc.perform(post("/cart-service/create-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated());
        waitForRecord("ORD-B200"); // Consume create message

        // Update to confirmed (valid transition from new)
        UpdateOrderRequest updateRequest = new UpdateOrderRequest("B200", "confirmed");

        mockMvc.perform(put("/cart-service/update-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk());
        
        // Verify Kafka message
        ConsumerRecord<String, String> record = waitForRecord("ORD-B200");
        assertNotNull(record);
        assertEquals("ORD-B200", record.key());
        assertTrue(record.value().contains("confirmed"));
    }

    @Test
    void testCreateOrder_Duplicate_Conflict() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("C300", 1);
        
        // First request
        mockMvc.perform(post("/cart-service/create-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        waitForRecord("ORD-C300"); // consume

        // Second request
        mockMvc.perform(post("/cart-service/create-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void testCreateOrder_InvalidId_BadRequest() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("XYZ", 1); // Invalid hex
        
        mockMvc.perform(post("/cart-service/create-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpdateOrder_NotFound() throws Exception {
        UpdateOrderRequest request = new UpdateOrderRequest("FFFF", "confirmed");

        mockMvc.perform(put("/cart-service/update-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void testHealthEndpoints() throws Exception {
        mockMvc.perform(get("/cart-service/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/cart-service/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private ConsumerRecord<String, String> waitForRecord(String key) {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }
}

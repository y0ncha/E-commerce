package mta.eda.producer.service.order;

import mta.eda.producer.exception.DuplicateOrderException;
import mta.eda.producer.exception.InvalidOrderIdException;
import mta.eda.producer.exception.OrderNotFoundException;
import mta.eda.producer.exception.ServiceUnavailableException;
import mta.eda.producer.model.CreateOrderRequest;
import mta.eda.producer.model.Order;
import mta.eda.producer.model.UpdateOrderRequest;
import mta.eda.producer.service.kafka.KafkaProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private KafkaProducerService kafkaProducerService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(kafkaProducerService);
        // The default behavior for void sendOrder is to do nothing
    }

    @Test
    void createOrder_ValidRequest_ShouldCreateAndPublish() {
        CreateOrderRequest request = new CreateOrderRequest("123A", 1);
        
        Order createdOrder = orderService.createOrder(request);
        
        assertNotNull(createdOrder);
        assertEquals("ORD-123A", createdOrder.orderId());
        verify(kafkaProducerService, times(1)).sendOrder(eq("ORD-123A"), any(Order.class));
    }

    @Test
    void createOrder_DuplicateId_ShouldThrowException() {
        CreateOrderRequest request = new CreateOrderRequest("123B", 1);
        orderService.createOrder(request); // First creation

        assertThrows(DuplicateOrderException.class, () -> orderService.createOrder(request));
        
        // Verify sendOrder was called only once (for the first success)
        verify(kafkaProducerService, times(1)).sendOrder(eq("ORD-123B"), any(Order.class));
    }

    @Test
    void createOrder_InvalidIdFormat_ShouldThrowException() {
        CreateOrderRequest request = new CreateOrderRequest("12G3", 1); // G is invalid hex
        
        assertThrows(InvalidOrderIdException.class, () -> orderService.createOrder(request));
        
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void createOrder_ShortId_ShouldPadCorrectly() {
        CreateOrderRequest request = new CreateOrderRequest("A", 1);
        
        Order createdOrder = orderService.createOrder(request);
        
        assertEquals("ORD-000A", createdOrder.orderId());
    }

    @Test
    void updateOrder_ExistingOrder_ShouldUpdateAndPublish() {
        // Setup existing order
        CreateOrderRequest createRequest = new CreateOrderRequest("5555", 1);
        orderService.createOrder(createRequest);
        
        UpdateOrderRequest updateRequest = new UpdateOrderRequest("5555", "shipped");
        
        Order updatedOrder = orderService.updateOrder(updateRequest);
        
        assertEquals("shipped", updatedOrder.status());
        verify(kafkaProducerService, times(2)).sendOrder(anyString(), any());
    }

    @Test
    void updateOrder_NonExistentOrder_ShouldThrowException() {
        UpdateOrderRequest updateRequest = new UpdateOrderRequest("9999", "shipped");
        
        assertThrows(OrderNotFoundException.class, () -> orderService.updateOrder(updateRequest));
    }

    @Test
    void createOrder_KafkaFailure_ShouldRollback() {
        CreateOrderRequest request = new CreateOrderRequest("AAAA", 1);
        
        // Mock failure (4 arguments: type, orderId, message, cause)
        ServiceUnavailableException ex = new ServiceUnavailableException("KAFKA_UNAVAILABLE", "ORD-AAAA", "Kafka down", null);
        doThrow(ex).when(kafkaProducerService).sendOrder(anyString(), any());

        assertThrows(ServiceUnavailableException.class, () -> orderService.createOrder(request));

        // Reset mock to succeed second time
        reset(kafkaProducerService);
        doNothing().when(kafkaProducerService).sendOrder(anyString(), any());
        
        assertDoesNotThrow(() -> orderService.createOrder(request));
    }
}

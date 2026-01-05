package io.github.cyfko.example;

import io.github.cyfko.projection.Projected;
import io.github.cyfko.projection.Projection;

import java.math.BigDecimal;

@Projection(entity = Order.class)
public class OrderDTO {

    private Long id;

    private String orderNumber;

    @Projected(from = "totalAmount")
    private BigDecimal amount;

    // Getters and setters
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
package io.github.cyfko.example;

import io.github.cyfko.filterql.jpa.metamodel.Projected;
import io.github.cyfko.filterql.jpa.metamodel.Projection;

import java.math.BigDecimal;

@Projection(entity = Order.class)
public class OrderDTO {

    @Projected
    private long id;

    @Projected
    private String orderNumber;

    @Projected(from = "totalAmount")
    private BigDecimal amount;

    // Getters and setters
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
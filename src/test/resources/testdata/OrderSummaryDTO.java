package io.github.cyfko.example;

import io.github.cyfko.jpa.metamodel.Computed;
import io.github.cyfko.jpa.metamodel.Provider;
import io.github.cyfko.jpa.metamodel.Projected;
import io.github.cyfko.jpa.metamodel.Projection;

import java.math.BigDecimal;

@Projection(
    entity = Order.class,
    providers = {
            @Provider(value = TestComputationProvider.class, bean = "myBean")
    }
)
public class OrderSummaryDTO {
    
    @Projected(from = "orderNumber")
    private String orderNumber;
    
    @Projected(from = "totalAmount")
    private BigDecimal totalAmount;
    
    @Projected(from = "status")
    private Order.OrderStatus status;
    
    @Projected(from = "user.email")
    private String customerEmail;

    @Computed(dependsOn = {"user.firstName", "user.lastName"})
    private String customerName;

    @Computed(dependsOn = {"totalAmount"})
    private String formattedAmount;
    
    // Getters and setters
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    
    public Order.OrderStatus getStatus() { return status; }
    public void setStatus(Order.OrderStatus status) { this.status = status; }
    
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    
    public String getFormattedAmount() { return formattedAmount; }
    public void setFormattedAmount(String formattedAmount) { this.formattedAmount = formattedAmount; }
}
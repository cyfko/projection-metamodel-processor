package io.github.cyfko.example;

import io.github.cyfko.projection.Computed;
import io.github.cyfko.projection.Provider;
import io.github.cyfko.projection.Projected;
import io.github.cyfko.projection.Projection;

import java.util.List;

@Projection(from = User.class, providers = {
        @Provider(value = TestComputationProvider.class, bean = "myBean")
})
public class UserDTO {

    @Projected(from = "email")
    private String userEmail;

    @Projected(from = "address.city")
    private String city;

    @Projected(from = "department.name")
    private String departmentName;

    @Projected(from = "orders")
    private List<OrderDTO> orders;

    @Computed(dependsOn = { "firstName", "lastName" })
    private String fullName;

    @Computed(dependsOn = { "birthDate" })
    private Integer age; // Computed

    // Getters and setters
    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }
}
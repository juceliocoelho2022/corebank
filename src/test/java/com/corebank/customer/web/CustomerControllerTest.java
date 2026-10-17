package com.corebank.customer.web;

import com.corebank.customer.application.CustomerService;
import com.corebank.customer.domain.Customer;
import com.corebank.shared.exception.ApiExceptionHandler;
import com.corebank.shared.exception.ConflictException;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
@Import(ApiExceptionHandler.class)
class CustomerControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CustomerService service;

    @Test
    void createsCustomer() throws Exception {
        Customer customer = new Customer("Ana Silva", "12345678901", "ana@example.com");
        forceId(customer, UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(service.create("Ana Silva", "12345678901", "ana@example.com")).thenReturn(customer);

        mvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ana Silva","document":"12345678901","email":"ana@example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/customers/11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.name").value("Ana Silva"))
                .andExpect(jsonPath("$.email").value("ana@example.com"));
    }

    @Test
    void getsCustomer() throws Exception {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Customer customer = new Customer("Ana Silva", "12345678901", "ana@example.com");
        forceId(customer, id);
        when(service.get(id)).thenReturn(customer);

        mvc.perform(get("/api/v1/customers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void rejectsInvalidRequest() throws Exception {
        mvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"A","document":"","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void returnsConflictProblemDetail() throws Exception {
        when(service.create(any(), any(), any())).thenThrow(new ConflictException("A customer with this email already exists"));

        mvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ana Silva","document":"12345678901","email":"ana@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("A customer with this email already exists"));
    }

    @Test
    void returnsNotFoundProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new ResourceNotFoundException("Customer not found"));

        mvc.perform(get("/api/v1/customers/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void returnsBadRequestForMalformedUuid() throws Exception {
        mvc.perform(get("/api/v1/customers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'id'"));
    }

    private static void forceId(Customer customer, UUID id) throws Exception {
        var field = Customer.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(customer, id);
    }
}

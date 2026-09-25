package com.digitalbank.customerservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commons.exception.ConflictException;
import com.commons.exception.UpstreamException;
import com.digitalbank.customerservice.client.AuthServiceClient;
import com.digitalbank.customerservice.dto.CustomerRegistrationRequest;
import com.digitalbank.customerservice.mapper.CustomerMapper;
import com.digitalbank.customerservice.model.Customer;
import com.digitalbank.customerservice.model.KycStatus;
import com.digitalbank.customerservice.repository.CustomerRepository;

import org.assertj.core.api.Assertions;

/**
 * What happens to a customer when the identity provider refuses to register
 * them.
 *
 * <p>Verification is not a local flag. It means the customer exists in the
 * identity provider and can sign in, so a refusal from AuthUser has to leave
 * them unverified. The ordering in updateKycStatus already does that - the
 * registration call comes before the state change - and these tests pin it,
 * because reordering those lines would silently mark someone verified whose
 * identity was never created, and nothing else would notice.</p>
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceKycRefusalTest {

    private static final String EXTERNAL_ID = "cust-ext-1";

    @Mock private AuthServiceClient authServiceClient;
    @Mock private CustomerRepository repository;
    @Mock private CustomerMapper mapper;

    @InjectMocks private CustomerService customerService;

    private Customer pendingCustomer() {
        return Customer.builder()
                .id(1L)
                .version(3)
                .firstName("Ada")
                .lastName("Lovelace")
                .email("ada@example.com")
                .address("1 Analytical Way")
                .externalId(EXTERNAL_ID)
                .kycStatus(KycStatus.PENDING)
                .active(false)
                .build();
    }

    @Test
    @DisplayName("a refusal leaves the customer unverified and unsaved")
    void refusalLeavesCustomerUnverified() {
        Customer customer = pendingCustomer();
        when(repository.findByExternalId(EXTERNAL_ID)).thenReturn(Optional.of(customer));
        doThrow(new ConflictException(
                "That customer is already registered in the identity provider"))
                .when(authServiceClient).registerCustomer(any(CustomerRegistrationRequest.class));

        assertThatThrownBy(() -> customerService.updateKycStatus(EXTERNAL_ID, "VERIFIED"))
                .isInstanceOf(ConflictException.class);

        // The refusal must not be recorded as a verification.
        Assertions.assertThat(customer.getKycStatus()).isEqualTo(KycStatus.PENDING);
        Assertions.assertThat(customer.getActive()).isFalse();
        verify(repository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("the refusal is propagated, not swallowed into a success")
    void refusalIsPropagated() {
        Customer customer = pendingCustomer();
        when(repository.findByExternalId(EXTERNAL_ID)).thenReturn(Optional.of(customer));
        doThrow(new UpstreamException(
                "The customer could not be registered right now; please try again"))
                .when(authServiceClient).registerCustomer(any(CustomerRegistrationRequest.class));

        // Returning the version as though nothing happened would tell the
        // operator the customer is verified when they are not.
        assertThatThrownBy(() -> customerService.updateKycStatus(EXTERNAL_ID, "VERIFIED"))
                .isInstanceOf(UpstreamException.class);

        verify(repository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("a status other than VERIFIED does not reach the identity provider at all")
    void nonVerifiedStatusDoesNotRegister() {
        Customer customer = pendingCustomer();
        when(repository.findByExternalId(EXTERNAL_ID)).thenReturn(Optional.of(customer));

        customerService.updateKycStatus(EXTERNAL_ID, "REJECTED");

        verify(authServiceClient, never()).registerCustomer(any(CustomerRegistrationRequest.class));
        verify(repository, never()).save(any(Customer.class));
    }
}

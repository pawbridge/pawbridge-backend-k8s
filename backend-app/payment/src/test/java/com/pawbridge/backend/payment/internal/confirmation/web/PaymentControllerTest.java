package com.pawbridge.backend.payment.internal.confirmation.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/** Direct controller boundary only; actual JWT/filter/path validation is owned by Boot integration tests. */
class PaymentControllerTest {
    @Test void given__a_verified_principal__when__confirming__then__pass_that_identity_and_return_the_original_bare_response() {
        var payments=mock(Payments.class);var controller=new PaymentController(payments);
        var request=new TossPaymentConfirmRequest("synthetic-key","order-uuid",200L);
        var result=TossPaymentResponse.builder().status("ABORTED").build();
        when(payments.confirmPayment(7L,request)).thenReturn(result);
        var response=controller.confirmPayment(7L,request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getBody()).isSameAs(result);
        verify(payments).confirmPayment(7L,request);verifyNoMoreInteractions(payments);
    }
    @Test void given__no_verified_principal__when__confirming__then__reject_before_the_application() {
        var payments=mock(Payments.class);var controller=new PaymentController(payments);
        assertThatThrownBy(() -> controller.confirmPayment(null,new TossPaymentConfirmRequest("synthetic-key","order-uuid",200L)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(payments);
    }
}

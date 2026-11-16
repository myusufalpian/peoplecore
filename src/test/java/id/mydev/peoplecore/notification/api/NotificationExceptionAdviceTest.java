package id.mydev.peoplecore.notification.api;

import id.mydev.peoplecore.identity.api.controller.NotificationDestinationController;
import id.mydev.peoplecore.identity.application.service.NotificationDestinationService;
import id.mydev.peoplecore.notification.application.NotificationSupportService;
import id.mydev.peoplecore.notification.domain.InvitationReissueRequiredException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationExceptionAdviceTest {
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }
    @Test void invitationRepairDirectsTheOperatorToIdentityReissueWithoutAnySecretMaterial() {
        var response=new NotificationExceptionAdvice().identityReissueRequired(new InvitationReissueRequiredException());
        assertEquals(400,response.getStatusCode().value());
        assertEquals("INVITATION_REISSUE_REQUIRED",response.getBody().key());
        assertEquals("Use the authorized identity invitation reissue operation.",response.getBody().message());
    }
    @Test void invalidSupportRequestIs400WithoutEchoingPrivateDiagnostics() throws Exception {
        var service=mock(NotificationSupportService.class);
        when(service.list(any(),anyInt(),anyInt())).thenThrow(new IllegalArgumentException("private-address@example.test"));
        var mvc=MockMvcBuilders.standaloneSetup(new NotificationSupportController(service)).setControllerAdvice(new NotificationExceptionAdvice()).build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator",null,"SYSTEM_ADMIN"));
        mvc.perform(get("/api/v1/operations/notification-deliveries").param("page","-1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("VALIDATION_ERROR"))
            .andExpect(content().string(not(containsString("private-address@example.test"))));
    }
    @Test void invalidDestinationRequestUsesTheSameSafeBoundaryError() throws Exception {
        var service=mock(NotificationDestinationService.class);
        when(service.verify(any(),anyString(),anyString(),anyString(),anyString(),any()))
            .thenThrow(new IllegalArgumentException("sensitive-verification-detail"));
        var mvc=MockMvcBuilders.standaloneSetup(new NotificationDestinationController(service)).setControllerAdvice(new NotificationExceptionAdvice()).build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator",null,"SYSTEM_ADMIN"));
        mvc.perform(put("/api/v1/enrollments/employees/"+UUID.randomUUID()+"/notification-destination")
                .header("Idempotency-Key","verify-one").contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"verified@example.test\",\"evidenceRef\":\"verified-record\",\"reason\":\"Reviewed\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.key").value("VALIDATION_ERROR"))
            .andExpect(content().string(not(containsString("sensitive-verification-detail"))));
    }
}

package com.engine.querylens.infrastructure;

import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.infrastructure.webhook.WebhookAlertDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookAlertDispatcherTest {

    @Test
    @DisplayName("Should dispatch formatted webhook payload asynchronously")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldDispatchWebhook() throws Exception {
        HttpClient mockClient = Mockito.mock(HttpClient.class);
        HttpResponse mockResponse = Mockito.mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        doReturn(mockResponse).when(mockClient).send(any(HttpRequest.class), any());

        WebhookAlertDispatcher dispatcher = new WebhookAlertDispatcher("https://hooks.slack.com/services/test", mockClient);

        ViolationReport report = ViolationReport.builder(AntiPatternType.N_PLUS_ONE)
                .title("N+1 Detected")
                .description("5 queries in tx")
                .offendingQuery("SELECT * FROM customer WHERE id = ?")
                .build();

        dispatcher.dispatch(report);

        // Wait brief moment for virtual thread execution
        Thread.sleep(300);

        verify(mockClient).send(any(HttpRequest.class), any());
    }
}

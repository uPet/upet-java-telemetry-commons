package co.upet.commons.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.IHub;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class AsyncMessagesErrorReporterTest {

    private final IHub baseHub = mock(IHub.class);
    private final IHub hub = mock(IHub.class);
    private final MeterRegistry registry = mock(MeterRegistry.class);
    private final AsyncMessagesErrorReporter reporter = new AsyncMessagesErrorReporter(baseHub, "app", false, registry);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Message message = mock(Message.class);
    private final ArgumentCaptor<SentryEvent> eventCaptor = ArgumentCaptor.forClass(SentryEvent.class);
    private final ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

    private final Object simpleCommand = new Command<>("app.cmd", "42", normalize(new DummyData("hello", 10)));
    private final Object simpleQuery = new AsyncQuery<>("app.query",  normalize(new DummyData("hello", 10)));
    private final Object simpleEvent = new DomainEvent<>("app.event", "42", normalize(new DummyData("hello", 10)));

    private final String userEmail = "email@example.com";
    private final Long userId = 342L;

    private final Map<Class<?>, AsyncMessagesErrorReporter.MessageType> typesPerClass =
        Map.of(
            Command.class, AsyncMessagesErrorReporter.MessageType.COMMAND,
            DomainEvent.class, AsyncMessagesErrorReporter.MessageType.EVENT,
            AsyncQuery.class, AsyncMessagesErrorReporter.MessageType.QUERY
        );

    @Before
    public void init() {
        when(baseHub.clone()).thenReturn(hub);
    }

    @Test
    public void shouldSendCommandErrorReport() throws JsonProcessingException {
        reportAndAssertMessage(simpleCommand, "app.cmd", "42");
    }

    @Test
    public void shouldSendEventErrorReport() throws JsonProcessingException {
        reportAndAssertMessage(simpleEvent, "app.event", "42");
    }

    @Test
    public void shouldSendQueryErrorReport() throws JsonProcessingException {
        reportAndAssertMessage(simpleQuery, "app.query", "N/A");
    }

    @Test
    public void shouldExtractUserInformationIfPresent() throws JsonProcessingException {
        final DummyData data = createDataWithUser();
        final Object object = new Command<>("app.cmd", "42", normalize(data));
        doReportError(object, false);
        verifyUserInformationSent();
    }

    @Test
    public void shouldNotReportRedeliveredMessages() throws JsonProcessingException {
        doReportError(simpleQuery, true);
        verify(hub, never()).captureEvent(eventCaptor.capture());
        verify(hub, never()).setTransaction(anyString());
    }

    @Test
    public void shouldReportRedeliveredMessagesWhenConfigured() throws JsonProcessingException {
        final AsyncMessagesErrorReporter reporter = new AsyncMessagesErrorReporter(baseHub, "app", true, registry);
        doReportError(reporter, new RuntimeException("Test"), simpleCommand, true);
        assertMessageReport(simpleCommand, "app.cmd", "42");
    }

    @Test
    public void shouldReportWarningLevenWhenIsBusinessError() throws JsonProcessingException {
        doReportError(new BusinessDummyError("Test"), simpleCommand, false);
        verifyCaptureEvent(SentryLevel.WARNING);
    }

    private void reportAndAssertMessage(Object object, String messageName, String msgId) throws JsonProcessingException {
        doReportError(object, false);
        assertMessageReport(object, messageName, msgId);
    }

    private void assertMessageReport(Object object, String messageName, String msgId) throws JsonProcessingException {
        verifyTags(messageName, msgId, object.getClass());
        verify(hub).setTransaction("app-" + messageName);
        verify(hub).setExtra("message.body", new String(msgToBytes(object)));
        verifyCaptureEvent(SentryLevel.FATAL);
    }

    private void doReportError(Object object, boolean redelivered) throws JsonProcessingException {
        final RuntimeException ex = new RuntimeException("Test Ex 1");
        doReportError(ex, object, redelivered);
    }

    private void doReportError(Throwable throwable, Object object, boolean redelivered) throws JsonProcessingException {
        doReportError(reporter, throwable, object, redelivered);
    }

    private void doReportError(AsyncMessagesErrorReporter reporter, Throwable throwable, Object object, boolean redelivered) throws JsonProcessingException {
        when(message.getBody()).thenReturn(msgToBytes(object));
        reporter.reportError(throwable, message, object, redelivered)
            .as(StepVerifier::create)
            .verifyComplete()
        ;
    }

    private void verifyTags(String messageName, String msgId, Class<?> objClass) {
        verify(hub).setTag("upet.mtype", typesPerClass.get(objClass).name());
        verify(hub).setTag("upet.message.name", messageName);
        verify(hub).setTag("upet.microservice", "app");
        verify(hub).setTag("correlation-id", msgId);
        verify(hub).setTag("request-id", msgId);
    }

    private void verifyCaptureEvent(SentryLevel level) {
        verify(hub).captureEvent(eventCaptor.capture());
        final SentryEvent sentryEvent = eventCaptor.getValue();
        assertThat(sentryEvent.getLevel()).isEqualTo(level);
    }

    private void verifyUserInformationSent() {
        verify(hub).setUser(userCaptor.capture());
        final User user = userCaptor.getValue();
        assertThat(user.getEmail()).isEqualTo(userEmail);
        assertThat(user.getId()).isEqualTo(userId.toString());
        assertThat(user.getUsername()).isEqualTo(userEmail);
    }

    private DummyData createDataWithUser() {
        final DummyUser user =  new DummyUser("email@example.com",  342L);
        return new DummyData("hello", 10, user);
    }

    private byte[] msgToBytes(Object o) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(o);
    }

    private JsonNode normalize(Object data) {
        return objectMapper.valueToTree(data);
    }

    @Data
    @AllArgsConstructor
    @RequiredArgsConstructor
    private static class DummyData {
        private final String field0;
        private final Integer field1;
        private DummyUser user;
    }

    @Data
    @AllArgsConstructor
    private static class DummyUser {
        private final String email;
        private final Long id;
    }

    private static class BusinessDummyError extends RuntimeException{
        public BusinessDummyError(String message) {
            super(message);
        }
    }

}
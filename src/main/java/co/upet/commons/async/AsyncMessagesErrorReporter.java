package co.upet.commons.async;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.IHub;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.User;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.ext.CustomReporter;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import static co.upet.commons.async.JsonUtils.getText;
import static java.lang.String.format;

public class AsyncMessagesErrorReporter implements CustomReporter {

    public enum MessageType {QUERY, COMMAND, EVENT}

    private static final Logger LOGGER = Logger.getLogger(AsyncMessagesErrorReporter.class.getName());
    private static final String SENT_LOG_TEXT = "Reactive Commons Error Sent to Sentry, name: {0}, type: {1}, id: {2}";
    private static final String NO_SENT_LOG_TEXT = "Sentry report ignored of redelivered Message";
    private static final String NO_USER_LOG_TEXT = "No user data present in the message to sent to Sentry";

    private static final String TX_COUNTER = "async.message.completed.duration.count";
    private static final String TX_DURATION_COUNTER = "async.message.completed.duration";

    private final IHub baseHub;
    private final String serviceName;
    private final boolean reportRedelivered;
    private final MeterRegistry meterRegistry;

    public AsyncMessagesErrorReporter(IHub baseHub, String serviceName, boolean reportRedelivered, MeterRegistry registry) {
        this.baseHub = baseHub;
        this.serviceName = serviceName;
        this.reportRedelivered = reportRedelivered;
        this.meterRegistry = registry;
    }

    @Override
    public void reportMetric(String type, String handlerPath, Long duration, boolean success) {
        final String resultValue = success ? "success" : "failure";

        meterRegistry.counter(TX_COUNTER,
            "transaction", format("%s.%s", type, handlerPath),
            "result", resultValue
        ).increment();

        meterRegistry.counter(TX_DURATION_COUNTER,
            "transaction", format("%s.%s", type, handlerPath),
            "result", resultValue
        ).increment(duration);
    }

    @Override
    public Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered) {
        return whenShouldReport(redelivered, () ->
            sendReport(ex, rawMessage, message.getData(), message.getName(), message.getCommandId(), MessageType.COMMAND)
        );
    }

    @Override
    public Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered) {
        return whenShouldReport(redelivered, () ->
            sendReport(ex, rawMessage, message.getData(), message.getName(), message.getEventId(), MessageType.EVENT)
        );
    }

    @Override
    public Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered) {
        return whenShouldReport(redelivered, () ->
            sendReport(ex, rawMessage, message.getQueryData(), message.getResource(), "N/A", MessageType.QUERY)
        );
    }

    private Mono<Void> whenShouldReport(boolean redelivered, Supplier<Mono<Void>> reportFn) {
        if (!redelivered || reportRedelivered) {
            return reportFn.get();
        }
        LOGGER.log(Level.WARNING, NO_SENT_LOG_TEXT);
        return Mono.empty();
    }

    private Mono<Void> sendReport(Throwable ex, Message rawMessage, Object data, String name, String msgId, MessageType type) {
        final IHub hub = baseHub.clone();
        hub.pushScope();

        extractUserInformation(data, hub);
        setCommonTags(hub, type, name, msgId);
        hub.setExtra("message.body", new String(rawMessage.getBody()));

        hub.setFingerprint(Arrays.asList(
            "{{ default }}",
            serviceName,
            name
        ));

        return Mono.fromRunnable(() -> {
            capture(hub, ex, getLevel(ex));
            LOGGER.log(Level.INFO, SENT_LOG_TEXT, new Object[]{ name, type.name(), msgId});
            baseHub.popScope();
        });
    }

    private void setCommonTags(IHub hub, MessageType type, String name, String msgId) {
        hub.setTransaction(serviceName + "-" + name);
        hub.setTag("upet.mtype", type.name());
        hub.setTag("upet.message.name", name);
        hub.setTag("upet.microservice", serviceName);
        hub.setTag("correlation-id", msgId);
        hub.setTag("request-id", msgId);
    }

    private static void extractUserInformation(Object data, IHub hub) {
        if (data instanceof JsonNode && ((JsonNode) data).hasNonNull("user")) {
            final JsonNode userNode = ((JsonNode) data).get("user");
            final User user = new User();
            user.setEmail(getText(userNode, "email"));
            user.setUsername(getText(userNode, "email"));
            user.setId(getText(userNode, "id"));
            hub.setUser(user);
        }else {
            LOGGER.log(Level.INFO, NO_USER_LOG_TEXT);
        }
    }

    private static SentryLevel getLevel(Throwable ex) {
        if (ex.getClass().getName().contains("Business") ||
            (ex.getCause() != null && ex.getCause().getClass().getName().contains("Business"))) {
            return SentryLevel.WARNING;
        }
        return SentryLevel.FATAL;
    }

    private static void capture(IHub hub, Throwable ex, SentryLevel level) {
        final Mechanism mechanism = new Mechanism();
        mechanism.setHandled(false);
        final Throwable throwable =
            new ExceptionMechanismException(mechanism, ex, Thread.currentThread());
        final SentryEvent event = new SentryEvent(throwable);
        event.setLevel(level);
        hub.captureEvent(event);
    }
}

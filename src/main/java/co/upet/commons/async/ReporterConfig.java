package co.upet.commons.async;

import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.IHub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ReporterConfig {

    @Bean
    @Primary
    public AsyncMessagesErrorReporter messagesErrorReporter(@Value("${spring.application.name}") String app, IHub hub, MeterRegistry registry) {
        return new AsyncMessagesErrorReporter(hub, app, false, registry);
    }



}

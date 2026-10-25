package dev.ironjava.audit;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class RedactingMessageConverter extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return LogRedactor.redact(super.convert(event));
    }
}

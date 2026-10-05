package com.fardorado.notification.adapter.out.persistence.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fardorado.notification.domain.model.notification.EventType;

/**
 * Maps {@link EventType} between its Java enum name and its lower-case
 * snake_case database representation (e.g. {@code CREDIT_CARD_PAYMENT} is
 * stored as {@code credit_card_payment}).
 */
@Converter
public class EventTypeConverter implements AttributeConverter<EventType, String> {

    @Override
    public String convertToDatabaseColumn(EventType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public EventType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : EventType.valueOf(dbData.toUpperCase(java.util.Locale.ROOT));
    }
}

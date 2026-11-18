package com.hospitalqueue.common.security;

import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Makes a text column encrypted at rest: put {@code @Convert(converter = EncryptedStringConverter.class)} on an
 * entity field and the value is encrypted on the way into the database and decrypted on the way out, with no other
 * code needing to know. The column is plain TEXT holding the stored form described in {@link FieldEncryptor}.
 *
 * <p>Encrypted columns cannot be searched, sorted or indexed by their content; use them only for clinical free text
 * that is read by id.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldEncryptor fieldEncryptor;

    public EncryptedStringConverter(FieldEncryptor fieldEncryptor) {
        this.fieldEncryptor = fieldEncryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return fieldEncryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return fieldEncryptor.decrypt(dbData);
    }
}

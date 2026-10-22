package pl.instalflow.shared.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** The HTTP representation of decimal values and absolute timestamps. */
@Configuration(proxyBeanMethods = false)
public class JsonContractConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictScalarContract() {
        return builder -> builder
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Enum, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    JacksonModule apiValueContractModule() {
        SimpleModule module = new SimpleModule("instalflow-api-value-contract");
        module.addSerializer(BigDecimal.class, new DecimalStringSerializer());
        module.addDeserializer(BigDecimal.class, new DecimalStringDeserializer());
        module.addSerializer(Instant.class, new UtcInstantSerializer());
        module.addDeserializer(Instant.class, new OffsetInstantDeserializer());
        return module;
    }

    private static final class DecimalStringSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator output, SerializationContext context) {
            output.writeString(value.toPlainString());
        }
    }

    private static final class DecimalStringDeserializer extends ValueDeserializer<BigDecimal> {
        private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

        @Override
        public BigDecimal deserialize(JsonParser input, DeserializationContext context) {
            if (!input.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(BigDecimal.class, "Decimal values must be JSON strings");
            }
            String value = input.getString();
            if (!PLAIN_DECIMAL.matcher(value).matches()) {
                return context.reportInputMismatch(BigDecimal.class,
                        "Expected a plain decimal string without exponent notation");
            }
            return new BigDecimal(value);
        }
    }

    private static final class UtcInstantSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator output, SerializationContext context) {
            output.writeString(DateTimeFormatter.ISO_INSTANT.format(value));
        }
    }

    private static final class OffsetInstantDeserializer extends ValueDeserializer<Instant> {
        private static final Pattern OFFSET_TIMESTAMP = Pattern.compile(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}"
                        + "(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})");

        @Override
        public Instant deserialize(JsonParser input, DeserializationContext context) {
            if (!input.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(Instant.class, "Timestamp must be an ISO 8601 string with an offset");
            }
            String value = input.getString();
            if (!OFFSET_TIMESTAMP.matcher(value).matches()) {
                return context.reportInputMismatch(Instant.class,
                        "Timestamp must include seconds and an offset in Z or HH:mm format");
            }
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException invalidTimestamp) {
                return context.reportInputMismatch(Instant.class, "Timestamp must be an ISO 8601 string with an offset");
            }
        }
    }
}

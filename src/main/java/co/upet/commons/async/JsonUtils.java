package co.upet.commons.async;

import com.fasterxml.jackson.databind.JsonNode;

public class JsonUtils {

    public static String getText(final JsonNode node, final String fieldName) {
        if (node != null) {
            final JsonNode field = node.get(fieldName);
            if (field != null) {
                if (field.isTextual()) {
                    return field.asText();
                }
                if (field.isNumber()) {
                    return String.valueOf(field.longValue());
                }
            }
        }
        return null;
    }
}

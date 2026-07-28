package com.financeos.module.investment.command.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.regex.Pattern;

final class StrictDecimalStringDeserializer extends StdDeserializer<String> {
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?");

    StrictDecimalStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (!node.isTextual() || !DECIMAL.matcher(node.textValue()).matches()) {
            throw JsonMappingException.from(parser, "amounts must be plain decimal strings");
        }
        return node.textValue();
    }
}

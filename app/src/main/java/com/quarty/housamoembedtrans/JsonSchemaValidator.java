package com.quarty.housamoembedtrans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Small JSON Schema Draft 2020-12 validator for the keywords used by
 * scene_schema.json. Keeping the supported subset explicit avoids adding a
 * large runtime dependency to the LSPosed module.
 */
final class JsonSchemaValidator {

    private static final int MAX_DEPTH = 256;

    static final class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }

    private final JSONObject rootSchema;

    JsonSchemaValidator(JSONObject rootSchema) {
        this.rootSchema = rootSchema;
    }

    void validate(Object value) throws ValidationException {
        validateValue(value, rootSchema, "$", 0);
    }

    private void validateValue(
        Object value,
        JSONObject schema,
        String path,
        int depth
    ) throws ValidationException {
        if (depth > MAX_DEPTH) {
            throw error(path, "nesting is too deep");
        }

        String ref = schema.optString("$ref", "");
        if (!ref.isEmpty()) {
            validateValue(value, resolveRef(ref), path, depth + 1);
            return;
        }

        JSONArray oneOf = schema.optJSONArray("oneOf");
        if (oneOf != null) {
            int matches = 0;
            String firstFailure = null;
            for (int index = 0; index < oneOf.length(); index++) {
                try {
                    validateValue(
                        value,
                        oneOf.getJSONObject(index),
                        path,
                        depth + 1
                    );
                    matches++;
                } catch (ValidationException e) {
                    if (firstFailure == null) {
                        firstFailure = e.getMessage();
                    }
                } catch (Exception e) {
                    throw error(path, "oneOf contains an invalid schema");
                }
            }
            if (matches != 1) {
                String detail = matches == 0 && firstFailure != null
                    ? ": " + firstFailure
                    : "";
                throw error(path, "must match exactly one allowed shape" + detail);
            }
        }

        if (schema.has("const") && !jsonEquals(value, schema.opt("const"))) {
            throw error(path, "has an unexpected constant value");
        }

        JSONArray enumValues = schema.optJSONArray("enum");
        if (enumValues != null) {
            boolean found = false;
            for (int index = 0; index < enumValues.length(); index++) {
                if (jsonEquals(value, enumValues.opt(index))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw error(path, "is not one of the allowed values");
            }
        }

        String type = schema.optString("type", "");
        if (!type.isEmpty() && !matchesType(value, type)) {
            throw error(path, "must be " + type);
        }

        if (value instanceof JSONObject) {
            validateObject((JSONObject) value, schema, path, depth + 1);
        } else if (value instanceof JSONArray) {
            validateArray((JSONArray) value, schema, path, depth + 1);
        } else if (value instanceof String) {
            validateString((String) value, schema, path);
        }
    }

    private void validateObject(
        JSONObject object,
        JSONObject schema,
        String path,
        int depth
    ) throws ValidationException {
        JSONArray required = schema.optJSONArray("required");
        if (required != null) {
            for (int index = 0; index < required.length(); index++) {
                String key = required.optString(index, "");
                if (key.isEmpty() || !object.has(key)) {
                    throw error(path, "is missing required field " + key);
                }
            }
        }

        JSONObject properties = schema.optJSONObject("properties");
        Set<String> known = new HashSet<>();
        if (properties != null) {
            Iterator<String> propertyNames = properties.keys();
            while (propertyNames.hasNext()) {
                String key = propertyNames.next();
                known.add(key);
                if (object.has(key)) {
                    try {
                        validateValue(
                            object.get(key),
                            properties.getJSONObject(key),
                            childPath(path, key),
                            depth
                        );
                    } catch (ValidationException e) {
                        throw e;
                    } catch (Exception e) {
                        throw error(childPath(path, key), "has an invalid field schema");
                    }
                }
            }
        }

        Object additional = schema.opt("additionalProperties");
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (known.contains(key)) {
                continue;
            }
            if (Boolean.FALSE.equals(additional)) {
                throw error(childPath(path, key), "is not an allowed field");
            }
            if (additional instanceof JSONObject) {
                try {
                    validateValue(
                        object.get(key),
                        (JSONObject) additional,
                        childPath(path, key),
                        depth
                    );
                } catch (ValidationException e) {
                    throw e;
                } catch (Exception e) {
                    throw error(childPath(path, key), "could not be validated");
                }
            }
        }
    }

    private void validateArray(
        JSONArray array,
        JSONObject schema,
        String path,
        int depth
    ) throws ValidationException {
        int minItems = schema.optInt("minItems", -1);
        if (minItems >= 0 && array.length() < minItems) {
            throw error(path, "must contain at least " + minItems + " item(s)");
        }

        JSONObject itemSchema = schema.optJSONObject("items");
        if (itemSchema == null) {
            return;
        }

        for (int index = 0; index < array.length(); index++) {
            try {
                validateValue(
                    array.get(index),
                    itemSchema,
                    path + "[" + index + "]",
                    depth
                );
            } catch (ValidationException e) {
                throw e;
            } catch (Exception e) {
                throw error(path + "[" + index + "]", "could not be validated");
            }
        }
    }

    private static void validateString(
        String value,
        JSONObject schema,
        String path
    ) throws ValidationException {
        int minLength = schema.optInt("minLength", -1);
        if (minLength >= 0 && value.length() < minLength) {
            throw error(path, "must contain at least " + minLength + " character(s)");
        }

        int maxLength = schema.optInt("maxLength", -1);
        if (maxLength >= 0 && value.length() > maxLength) {
            throw error(path, "must contain no more than " + maxLength + " characters");
        }
    }

    private JSONObject resolveRef(String ref) throws ValidationException {
        if (!ref.startsWith("#/")) {
            throw error("$schema", "only local $ref values are supported: " + ref);
        }

        Object current = rootSchema;
        String[] segments = ref.substring(2).split("/");
        try {
            for (String segment : segments) {
                if (!(current instanceof JSONObject)) {
                    throw new IllegalArgumentException();
                }
                String key = segment.replace("~1", "/").replace("~0", "~");
                current = ((JSONObject) current).get(key);
            }
        } catch (Exception e) {
            throw error("$schema", "cannot resolve $ref " + ref);
        }

        if (!(current instanceof JSONObject)) {
            throw error("$schema", "$ref does not point to an object: " + ref);
        }
        return (JSONObject) current;
    }

    private static boolean matchesType(Object value, String type) {
        switch (type) {
            case "object":
                return value instanceof JSONObject;
            case "array":
                return value instanceof JSONArray;
            case "string":
                return value instanceof String;
            case "integer":
                return value instanceof Number
                    && Double.isFinite(((Number) value).doubleValue())
                    && ((Number) value).doubleValue()
                        == Math.rint(((Number) value).doubleValue());
            case "number":
                return value instanceof Number
                    && Double.isFinite(((Number) value).doubleValue());
            case "boolean":
                return value instanceof Boolean;
            case "null":
                return value == null || value == JSONObject.NULL;
            default:
                return false;
        }
    }

    private static boolean jsonEquals(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(
                ((Number) left).doubleValue(),
                ((Number) right).doubleValue()
            ) == 0;
        }
        return left.equals(right);
    }

    private static String childPath(String path, String key) {
        return path + "." + key;
    }

    private static ValidationException error(String path, String message) {
        return new ValidationException(path + " " + message);
    }
}

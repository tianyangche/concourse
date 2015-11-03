/*
 * Copyright (c) 2013-2015 Cinchapi Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cinchapi.concourse.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

import com.cinchapi.concourse.Concourse;
import com.cinchapi.concourse.Link;
import com.cinchapi.concourse.Tag;
import com.cinchapi.concourse.annotate.PackagePrivate;
import com.cinchapi.concourse.annotate.UtilityClass;
import com.cinchapi.concourse.thrift.Operator;
import com.cinchapi.concourse.thrift.TObject;
import com.cinchapi.concourse.thrift.Type;
import com.google.common.base.Objects;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Longs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A collection of functions to convert objects. The public API defined in
 * {@link Concourse} uses certain objects for convenience that are not
 * recognized by Thrift, so it is necessary to convert back and forth between
 * different representations.
 * 
 * @author Jeff Nelson
 */
@UtilityClass
public final class Convert {

    /**
     * Takes a JSON string representation of an object or an array of JSON
     * objects and returns a list of {@link Multimap multimaps} with the
     * corresponding data. Unlike {@link #jsonToJava(String)}, this method will
     * allow the top level element to be an array in the {code json} string.
     * 
     * @param json
     * @return A list of Java objects
     */
    public static List<Multimap<String, Object>> anyJsonToJava(String json) {
        List<Multimap<String, Object>> result = Lists.newArrayList();
        JsonElement top = JSON_PARSER.parse(json);
        if(top.isJsonArray()) {
            JsonArray array = (JsonArray) top;
            Iterator<JsonElement> it = array.iterator();
            while (it.hasNext()) {
                result.add(jsonToJava(it.next().toString()));
            }
        }
        else {
            result.add(jsonToJava(top.toString()));
        }
        return result;
    }

    /**
     * Return the Thrift Object that represents {@code object}.
     * 
     * @param object
     * @return the TObject
     */
    public static TObject javaToThrift(Object object) {
        ByteBuffer bytes;
        Type type = null;
        if(object instanceof Boolean) {
            bytes = ByteBuffer.allocate(1);
            bytes.put((boolean) object ? (byte) 1 : (byte) 0);
            type = Type.BOOLEAN;
        }
        else if(object instanceof Double) {
            bytes = ByteBuffer.allocate(8);
            bytes.putDouble((double) object);
            type = Type.DOUBLE;
        }
        else if(object instanceof Float) {
            bytes = ByteBuffer.allocate(4);
            bytes.putFloat((float) object);
            type = Type.FLOAT;
        }
        else if(object instanceof Link) {
            bytes = ByteBuffer.allocate(8);
            bytes.putLong(((Link) object).longValue());
            type = Type.LINK;
        }
        else if(object instanceof Long) {
            bytes = ByteBuffer.allocate(8);
            bytes.putLong((long) object);
            type = Type.LONG;
        }
        else if(object instanceof Integer) {
            bytes = ByteBuffer.allocate(4);
            bytes.putInt((int) object);
            type = Type.INTEGER;
        }
        else if(object instanceof Tag) {
            bytes = ByteBuffer.wrap(object.toString().getBytes(
                    StandardCharsets.UTF_8));
            type = Type.TAG;
        }
        else {
            bytes = ByteBuffer.wrap(object.toString().getBytes(
                    StandardCharsets.UTF_8));
            type = Type.STRING;
        }
        bytes.rewind();
        return new TObject(bytes, type).setJavaFormat(object);
    }

    /**
     * Convert a JSON formatted string to a mapping that associates each key
     * with the Java objects that represent the corresponding values. This
     * method is designed to parse simple JSON structures that associate keys to
     * simple values or arrays without knowing the type of each element ahead of
     * time.
     * <p>
     * This method can properly handle JSON strings that abide by the following
     * rules:
     * <ul>
     * <li>The top level element in the JSON string must be an Object</li>
     * <li>No nested objects (e.g. a key cannot map to an object)</li>
     * <li>No null values</li>
     * </ul>
     * </p>
     * 
     * @param json
     * @return the converted data
     */
    public static Multimap<String, Object> jsonToJava(String json) {
        // NOTE: in this method we use the #toString instead of the #getAsString
        // method of each JsonElement to trigger the conversion to a java
        // primitive to ensure that quotes are taken into account and we
        // properly convert strings masquerading as numbers (e.g. "3").
        Multimap<String, Object> data = HashMultimap.create();
        JsonParser parser = new JsonParser();
        JsonElement top = parser.parse(json);
        if(!top.isJsonObject()) {
            throw new JsonParseException(
                    "The JSON string must encapsulate data within an object");
        }
        JsonObject object = (JsonObject) parser.parse(json);
        for (Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            JsonElement val = entry.getValue();
            if(val.isJsonArray()) {
                // If we have an array, add the elements individually. If there
                // are any duplicates in the array, they will be filtered out by
                // virtue of the fact that a HashMultimap does not store
                // dupes.
                Iterator<JsonElement> it = val.getAsJsonArray().iterator();
                while (it.hasNext()) {
                    JsonElement elt = it.next();
                    if(elt.isJsonPrimitive()) {
                        Object value = jsonElementToJava(elt);
                        data.put(key, value);
                    }
                    else {
                        throw new JsonParseException(
                                "Cannot parse a non-primitive "
                                        + "element inside of an array");
                    }
                }
            }
            else {
                Object value = jsonElementToJava(val);
                data.put(key, value);
            }
        }
        return data;
    }

    /**
     * Convert the {@code operator} to a string representation.
     * 
     * @param operator
     * @return the operator string
     */
    public static String operatorToString(Operator operator) {
        String string = "";
        switch (operator) {
        case EQUALS:
            string = "=";
            break;
        case NOT_EQUALS:
            string = "!=";
            break;
        case GREATER_THAN:
            string = ">";
            break;
        case GREATER_THAN_OR_EQUALS:
            string = ">=";
            break;
        case LESS_THAN:
            string = "<";
            break;
        case LESS_THAN_OR_EQUALS:
            string = "<=";
            break;
        case BETWEEN:
            string = "><";
            break;
        default:
            string = operator.name();
            break;

        }
        return string;
    }

    /**
     * Analyze {@code value} and convert it to the appropriate Java primitive or
     * Object.
     * <p>
     * <h1>Conversion Rules</h1>
     * <ul>
     * <li><strong>String</strong> - the value is converted to a string if it
     * starts and ends with matching single (') or double ('') quotes.
     * Alternatively, the value is converted to a string if it cannot be
     * converted to another type</li>
     * <li><strong>{@link ResolvableLink}</strong> - the value is converted to a
     * ResolvableLink if it is a properly formatted specification returned from
     * the {@link #stringToResolvableLinkSpecification(String, String)} method
     * (<strong>NOTE: </strong> this is a rare case)</li>
     * <li><strong>{@link Link}</strong> - the value is converted to a Link if
     * it is an int or long that is wrapped by '@' signs (i.e. @1234@)</li>
     * <li><strong>Boolean</strong> - the value is converted to a Boolean if it
     * is equal to 'true', or 'false' regardless of case</li>
     * <li><strong>Double</strong> - the value is converted to a double if and
     * only if it is a decimal number that is immediately followed by a single
     * capital "D" (e.g. 3.14D)</li>
     * <li><strong>Tag</strong> - the value is converted to a Tag if it starts
     * and ends with matching (`) quotes</li>
     * <li><strong>Integer, Long, Float</strong> - the value is converted to a
     * non double number depending upon whether it is a standard integer (e.g.
     * less than {@value java.lang.Integer#MAX_VALUE}), a long, or a floating
     * point decimal</li>
     * </ul>
     * </p>
     * 
     * 
     * @param value
     * @return the converted value
     */
    public static Object stringToJava(String value) {
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        Long record;
        if(Strings.isWithinQuotes(value)) {
            // keep value as string since its between single or double quotes
            return value.substring(1, value.length() - 1);
        }
        else if(first == '@'
                && last == '@'
                && (record = Longs.tryParse(value.substring(1,
                        value.length() - 1))) != null) {
            return Link.to(record);
        }
        else if(first == '@' && last == '@'
                && STRING_RESOLVABLE_LINK_REGEX.matcher(value).matches()) {
            String ccl = value.substring(1, value.length() - 1);
            return ResolvableLink.create(ccl);

        }
        else if(value.equalsIgnoreCase("true")) {
            return true;
        }
        else if(value.equalsIgnoreCase("false")) {
            return false;
        }
        else if(first == '`' && last == '`') {
            return Tag.create(value.substring(1, value.length() - 1));
        }
        else {
            return Objects.firstNonNull(Strings.tryParseNumber(value), value);
        }
    }

    /**
     * Convert the {@code symbol} to the corresponding {@link Operator}.
     * These include strings such as (=, >, >=, etc), and CaSH symbols (eq, gt,
     * gte, etc).
     * 
     * @param symbol
     * @return
     */
    public static Operator stringToOperator(String symbol) {
        switch (symbol.toLowerCase()) {
        case "==":
        case "=":
        case "eq":
            return Operator.EQUALS;
        case "!=":
        case "ne":
            return Operator.NOT_EQUALS;
        case ">":
        case "gt":
            return Operator.GREATER_THAN;
        case ">=":
        case "gte":
            return Operator.GREATER_THAN_OR_EQUALS;
        case "<":
        case "lt":
            return Operator.LESS_THAN;
        case "<=":
        case "lte":
            return Operator.LESS_THAN_OR_EQUALS;
        case "><":
        case "bw":
            return Operator.BETWEEN;
        case "->":
        case "lnk2":
        case "lnks2":
            return Operator.LINKS_TO;
        case "regex":
            return Operator.REGEX;
        case "nregex":
            return Operator.NOT_REGEX;
        case "like":
            return Operator.LIKE;
        case "nlike":
            return Operator.NOT_LIKE;
        default:
            throw new IllegalStateException("Cannot parse " + symbol
                    + " into an operator");
        }
    }

    /**
     * <p>
     * <strong>USE WITH CAUTION: </strong> This conversation is only necessary
     * for applications that import raw data but cannot use the Concourse API
     * directly and therefore cannot explicitly add links (e.g. the
     * import-framework that handles raw string data). <strong>
     * <em>If you have access to the Concourse API, you should not use this 
     * method!</em> </strong>
     * </p>
     * <p>
     * Convert the {@code ccl} string to a {@link ResolvableLink} instruction
     * for the receiver to add links to all the records that match the criteria.
     * </p>
     * <p>
     * Please note that this method only returns a specification and not an
     * actual {@link ResolvableLink} object. Use the
     * {@link #stringToJava(String)} method on the value returned from this
     * method to get the object.
     * </p>
     * 
     * @param ccl - The criteria to use when resolving link targets
     * @return An instruction to create a {@link ResolvableLink}
     */
    public static String stringToResolvableLinkInstruction(String ccl) {
        return Strings.joinSimple(RAW_RESOLVABLE_LINK_SYMBOL_PREPEND, ccl,
                RAW_RESOLVABLE_LINK_SYMBOL_APPEND);
    }

    /**
     * <p>
     * <strong>USE WITH CAUTION: </strong> This conversation is only necessary
     * for applications that import raw data but cannot use the Concourse API
     * directly and therefore cannot explicitly add links (e.g. the
     * import-framework that handles raw string data). <strong>
     * <em>If you have access to the Concourse API, you should not use this 
     * method!</em> </strong>
     * </p>
     * Convert the {@code rawValue} into a {@link ResolvableLink} specification
     * that instructs the receiver to add a link to all the records that have
     * {@code rawValue} mapped from {@code key}.
     * <p>
     * Please note that this method only returns a specification and not an
     * actual {@link ResolvableLink} object. Use the
     * {@link #stringToJava(String)} method on the value returned from this
     * method to get the object.
     * </p>
     * 
     * @param key
     * @param rawValue
     * @return the transformed value.
     */
    @Deprecated
    public static String stringToResolvableLinkSpecification(String key,
            String rawValue) {
        return stringToResolvableLinkInstruction(Strings.joinWithSpace(key,
                "=", rawValue));
    }

    /**
     * Return the Java Object that represents {@code object}.
     * 
     * @param object
     * @return the Object
     */
    public static Object thriftToJava(TObject object) {
        Object java = object.getJavaFormat();
        if(java == null) {
            ByteBuffer buffer = object.bufferForData();
            switch (object.getType()) {
            case BOOLEAN:
                java = ByteBuffers.getBoolean(buffer);
                break;
            case DOUBLE:
                java = buffer.getDouble();
                break;
            case FLOAT:
                java = buffer.getFloat();
                break;
            case INTEGER:
                java = buffer.getInt();
                break;
            case LINK:
                java = Link.to(buffer.getLong());
                break;
            case LONG:
                java = buffer.getLong();
                break;
            case TAG:
                java = ByteBuffers.getString(buffer);
                break;
            case NULL:
                java = null;
                break;
            default:
                java = ByteBuffers.getString(buffer);
                break;
            }
            buffer.rewind();
        }
        return java;
    }

    /**
     * Convert a {@link JsonElement} to a a Java object and respect the desire
     * to force a numeric string to a double.
     * 
     * @param element
     * @return the java object
     */
    private static Object jsonElementToJava(JsonElement element) {
        String asString = element.getAsString();
        if(element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                && (Strings.tryParseNumberStrict(asString) != null || Strings
                        .tryParseBoolean(asString) != null)) {
            return asString;
        }
        else {
            return stringToJava(asString);
        }
    }

    /**
     * The component of a resolvable link symbol that comes after the
     * resolvable key specification in the raw data.
     */
    @PackagePrivate
    static final String RAW_RESOLVABLE_LINK_SYMBOL_APPEND = "@"; // visible
                                                                 // for
                                                                 // testing

    /**
     * The component of a resolvable link symbol that comes before the
     * resolvable key specification in the raw data.
     */
    @PackagePrivate
    static final String RAW_RESOLVABLE_LINK_SYMBOL_PREPEND = "@"; // visible
                                                                  // for
                                                                  // testing

    /**
     * A parser to convert JSON documents represented as Strings to
     * {@link JsonElement json elements}.
     */
    private static final JsonParser JSON_PARSER = new JsonParser();

    /**
     * A {@link Pattern} that can be used to determine whether a string matches
     * the expected pattern of an instruction to insert links to records that
     * are resolved by finding matches to a criteria.
     */
    // NOTE: This REGEX enforces that the string must contain at least one
    // space, which means that a CCL string can only be considered valid if it
    // contains a space (e.g. name=jeff is not valid CCL).
    private static final Pattern STRING_RESOLVABLE_LINK_REGEX = Pattern
            .compile("^@(?=.*[ ]).+@$");

    private Convert() {/* Utility Class */}

    /**
     * A special class that is used to indicate that the record to which a Link
     * should point must be resolved by finding all records that match a
     * criteria.
     * <p>
     * This class is NOT part of the public API, so it should not be used as a
     * value for input to the client. Objects of this class exist merely to
     * provide utilities that depend on the client with instructions for
     * resolving a link in cases when the end-user of the utility cannot use the
     * client directly themselves (i.e. specifying a resolvable link in a raw
     * text file for the import framework).
     * </p>
     * <p>
     * To get an object of this class, call {@link Convert#stringToJava(String)}
     * on the result of calling
     * {@link Convert#stringToResolvableLinkInstruction(String)} on the raw
     * data.
     * </p>
     * 
     * @author Jeff Nelson
     */
    @Immutable
    public static final class ResolvableLink {

        // NOTE: This class does not define #hashCode() or #equals() because the
        // defaults are the desired behaviour

        /**
         * Create a new {@link ResolvableLink} that provides instructions to
         * create links to all the records that match the {@code ccl} string.
         * 
         * @param ccl - The criteria to use when resolving link targets
         * @return the ResolvableLink
         */
        @PackagePrivate
        static ResolvableLink create(String ccl) {
            return new ResolvableLink(ccl);
        }

        /**
         * Create a new {@link ResolvableLink} that provides instructions to
         * create a link to the records which contain {@code value} for
         * {@code key}.
         * 
         * @param key
         * @param value
         * @return the ResolvableLink
         */
        @PackagePrivate
        @Deprecated
        static ResolvableLink newResolvableLink(String key, Object value) {
            return new ResolvableLink(key, value);
        }

        @Deprecated
        protected final String key;

        @Deprecated
        protected final Object value;

        /**
         * The CCL string that should be used when resolving the link targets.
         */
        private final String ccl;

        /**
         * Construct a new instance.
         * 
         * @param ccl - The criteria to use when resolving link targets
         */
        private ResolvableLink(String ccl) {
            this.ccl = ccl;
            this.key = null;
            this.value = null;
        }

        /**
         * Construct a new instance.
         * 
         * @param key
         * @param value
         * @deprecated As of version 0.5.0
         */
        @Deprecated
        private ResolvableLink(String key, Object value) {
            this.ccl = new StringBuilder().append(key).append(" = ")
                    .append(value).toString();
            this.key = key;
            this.value = value;
        }

        /**
         * Return the {@code ccl} string that should be used for resolving link
         * targets.
         * 
         * @return {@link #ccl}
         */
        public String getCcl() {
            return ccl;
        }

        /**
         * Return the associated key.
         * 
         * @return the key
         */
        @Deprecated
        public String getKey() {
            return key;
        }

        /**
         * Return the associated value.
         * 
         * @return the value
         */
        @Deprecated
        public Object getValue() {
            return value;
        }

        @Override
        public String toString() {
            return Strings.joinWithSpace(this.getClass().getSimpleName(),
                    "for", ccl);
        }

    }

}
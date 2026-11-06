/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.json.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Number Length Constraint Bypass in Non-Blocking (Async) JSON Parsers
 * (GHSA-72hv-8253-57qq / CVE-2026-18401) for the shaded jackson-core
 * non-blocking parser: arbitrarily long numbers must be rejected instead of
 * being buffered and handed out for (super-linear) number conversion.
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class AsyncLargeNumberReadTest {

    private static final int TEST_NUMBER_LENGTH = StreamReadConstraints.DEFAULT_MAX_NUM_LEN * 2;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final JsonFactory jsonFactory = new JsonFactory();

    @Test
    public void asyncParserFailsTooLongInt() throws Exception {
        byte[] payload = utf8Bytes("{\"v\":" + digits(TEST_NUMBER_LENGTH) + "}");
        asyncParserFailsTooLongNumber(payload, JsonToken.VALUE_NUMBER_INT);
    }

    @Test
    public void asyncParserFailsTooLongNegativeInt() throws Exception {
        byte[] payload = utf8Bytes("[-" + digits(TEST_NUMBER_LENGTH) + "]");
        asyncParserFailsTooLongNumber(payload, JsonToken.VALUE_NUMBER_INT);
    }

    @Test
    public void asyncParserFailsTooLongRootIntAtEndOfInput() throws Exception {
        byte[] payload = utf8Bytes(digits(TEST_NUMBER_LENGTH));
        asyncParserFailsTooLongNumber(payload, JsonToken.VALUE_NUMBER_INT);
    }

    @Test
    public void asyncParserFailsTooLongDecimal() throws Exception {
        byte[] payload = utf8Bytes("{\"v\":0." + digits(TEST_NUMBER_LENGTH) + "}");
        asyncParserFailsTooLongNumber(payload, JsonToken.VALUE_NUMBER_FLOAT);
    }

    @Test
    public void asyncParserFailsTooLongDecimalWithExponent() throws Exception {
        // same as upstream test: exponent digits run up to the end of input
        byte[] payload = utf8Bytes("{\"v\":1.1E" + digits(TEST_NUMBER_LENGTH));
        asyncParserFailsTooLongNumber(payload, JsonToken.VALUE_NUMBER_FLOAT);
    }

    @Test
    public void asyncParserFailsTooLongDecimalWithExponentInObject() throws Exception {
        byte[] payload = utf8Bytes("{\"v\":1.1E" + digits(TEST_NUMBER_LENGTH) + "}");
        asyncParserFailsTooLongNumber(payload, JsonToken.VALUE_NUMBER_FLOAT);
    }

    @Test
    public void asyncParserFailsTooLongIntFedInChunks() throws Exception {
        byte[] payload = utf8Bytes("{\"v\":" + digits(TEST_NUMBER_LENGTH) + "}");
        asyncParserFailsTooLongNumberFedInChunks(payload, 7);
    }

    @Test
    public void asyncParserFailsTooLongDecimalFedInChunks() throws Exception {
        byte[] payload = utf8Bytes("{\"v\":-12.3" + digits(TEST_NUMBER_LENGTH) + "}");
        asyncParserFailsTooLongNumberFedInChunks(payload, 13);
    }

    @Test
    public void asyncParserFailsTooLongExponentFedInChunks() throws Exception {
        byte[] payload = utf8Bytes("{\"v\":1.5e-" + digits(TEST_NUMBER_LENGTH) + "}");
        asyncParserFailsTooLongNumberFedInChunks(payload, 11);
    }

    @Test
    public void asyncParserAcceptsNumbersWithinLimit() throws Exception {
        String intDigits = digits(StreamReadConstraints.DEFAULT_MAX_NUM_LEN);
        String fractDigits = digits(StreamReadConstraints.DEFAULT_MAX_NUM_LEN - 2);
        String expDigits = digits(StreamReadConstraints.DEFAULT_MAX_NUM_LEN - 3);
        byte[] payload = utf8Bytes("[" + intDigits + ",0." + fractDigits + ",1.1e" + expDigits + "]");

        JsonParser p = createAsyncParser(payload);
        try {
            assertEquals(JsonToken.START_ARRAY, p.nextToken());
            assertEquals(JsonToken.VALUE_NUMBER_INT, p.nextToken());
            assertEquals(intDigits, p.getText());
            assertEquals(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
            assertEquals("0." + fractDigits, p.getText());
            assertEquals(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
            assertEquals("1.1e" + expDigits, p.getText());
            assertEquals(JsonToken.END_ARRAY, p.nextToken());
            assertNull(p.nextToken());
        } finally {
            p.close();
        }
    }

    private void asyncParserFailsTooLongNumber(byte[] payload, JsonToken tokenMatch) throws Exception {
        JsonParser p = createAsyncParser(payload);
        boolean foundNumber = false;
        try {
            while (p.nextToken() != null) {
                if (p.getCurrentToken() == tokenMatch) {
                    foundNumber = true;
                    String numberText = p.getText();
                    assertEquals("Async parser silently accepted all " + TEST_NUMBER_LENGTH + " digits",
                            TEST_NUMBER_LENGTH, numberText.length());
                }
            }
            fail("Async parser must reject a " + TEST_NUMBER_LENGTH + "-digit number (number found? " + foundNumber + ")");
        } catch (StreamConstraintsException e) {
            verifyException(e);
        } finally {
            p.close();
        }
    }

    private void asyncParserFailsTooLongNumberFedInChunks(byte[] payload, int chunkSize) throws Exception {
        JsonParser p = jsonFactory.createNonBlockingByteArrayParser();
        ByteArrayFeeder feeder = (ByteArrayFeeder) p.getNonBlockingInputFeeder();
        int offset = 0;
        boolean ended = false;
        try {
            while (true) {
                JsonToken token = p.nextToken();
                if (token == JsonToken.NOT_AVAILABLE) {
                    assertTrue("Async parser requested more input after end-of-input", !ended);
                    if (offset < payload.length) {
                        int end = Math.min(offset + chunkSize, payload.length);
                        feeder.feedInput(payload, offset, end);
                        offset = end;
                    } else {
                        feeder.endOfInput();
                        ended = true;
                    }
                    continue;
                }
                if (token == null) {
                    break;
                }
                if (token.isNumeric()) {
                    fail("Async parser silently accepted number of " + p.getText().length() + " characters");
                }
            }
            fail("Async parser must reject a " + TEST_NUMBER_LENGTH + "-digit number fed in chunks");
        } catch (StreamConstraintsException e) {
            verifyException(e);
        } finally {
            p.close();
        }
    }

    private JsonParser createAsyncParser(byte[] payload) throws IOException {
        JsonParser p = jsonFactory.createNonBlockingByteArrayParser();
        ByteArrayFeeder feeder = (ByteArrayFeeder) p.getNonBlockingInputFeeder();
        feeder.feedInput(payload, 0, payload.length);
        feeder.endOfInput();
        return p;
    }

    private static void verifyException(StreamConstraintsException e) {
        String msg = e.getMessage();
        assertTrue("Unexpected exception message: " + msg, msg.contains("Number value length ("));
        assertTrue("Unexpected exception message: " + msg, msg.contains(" exceeds the maximum allowed"));
    }

    private static String digits(int numDigits) {
        StringBuilder sb = new StringBuilder(numDigits);
        for (int i = 0; i < numDigits; i++) {
            sb.append((char) ('1' + (i % 9)));
        }
        return sb.toString();
    }

    private static byte[] utf8Bytes(String str) {
        return str.getBytes(UTF8);
    }
}

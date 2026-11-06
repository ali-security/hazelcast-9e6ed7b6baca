package com.fasterxml.jackson.core;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;

/**
 * The constraints to use for streaming reads: used to guard against malicious
 * input by preventing processing of "too big" input constructs (values,
 * structures).
 *<p>
 * Currently only maximum number length is constrained, and it is enforced by the
 * non-blocking (async) parser.
 */
public class StreamReadConstraints
{
    /**
     * Default setting for maximum length: see {@link Builder#maxNumberLength(int)} for details.
     */
    public static final int DEFAULT_MAX_NUM_LEN = 1000;

    private static final StreamReadConstraints DEFAULT =
        new StreamReadConstraints(DEFAULT_MAX_NUM_LEN);

    private final int _maxNumLen;

    StreamReadConstraints(int maxNumLen) {
        _maxNumLen = maxNumLen;
    }

    public static StreamReadConstraints defaults() {
        return DEFAULT;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Accessor for maximum number of characters allowed in a number token.
     *
     * @return Maximum allowed number length
     */
    public int getMaxNumberLength() {
        return _maxNumLen;
    }

    /**
     * Convenience method that can be used to verify that an integer
     * (no floating point) number of specified length does not exceed
     * maximum specified by this constraints object.
     *
     * @param length Length of number in characters
     *
     * @throws StreamConstraintsException If length exceeds maximum
     */
    public void validateIntegerLength(int length) throws StreamConstraintsException
    {
        if (length > _maxNumLen) {
            throw new StreamConstraintsException(
                String.format("Number value length (%d) exceeds the maximum allowed (%d)",
                    length, _maxNumLen));
        }
    }

    /**
     * Convenience method that can be used to verify that a floating-point
     * number of specified length does not exceed maximum specified by this
     * constraints object.
     *
     * @param length Length of number in characters
     *
     * @throws StreamConstraintsException If length exceeds maximum
     */
    public void validateFPLength(int length) throws StreamConstraintsException
    {
        if (length > _maxNumLen) {
            throw new StreamConstraintsException(
                String.format("Number value length (%d) exceeds the maximum allowed (%d)",
                    length, _maxNumLen));
        }
    }

    public static final class Builder {
        private int maxNumLen = DEFAULT_MAX_NUM_LEN;

        Builder() { }

        /**
         * Sets the maximum number length (in chars or bytes, depending on input context).
         * The default is 1000.
         *
         * @param maxNumLen the maximum number length (in chars or bytes, depending on input context)
         *
         * @return this builder
         */
        public Builder maxNumberLength(int maxNumLen) {
            this.maxNumLen = maxNumLen;
            return this;
        }

        public StreamReadConstraints build() {
            return new StreamReadConstraints(maxNumLen);
        }
    }
}

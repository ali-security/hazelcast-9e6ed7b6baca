package com.fasterxml.jackson.core.exc;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Exception type used to indicate violations of stream constraints
 * (for example {@link com.fasterxml.jackson.core.StreamReadConstraints})
 * when reading or writing content.
 */
public class StreamConstraintsException extends JsonProcessingException
{
    private static final long serialVersionUID = 1L;

    public StreamConstraintsException(String msg) {
        super(msg);
    }
}

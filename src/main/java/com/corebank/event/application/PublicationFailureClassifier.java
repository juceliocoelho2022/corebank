package com.corebank.event.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
public class PublicationFailureClassifier {

    public boolean isRetriable(Throwable failure) {
        Throwable root = rootCause(failure);
        if (root instanceof JsonProcessingException || root instanceof SerializationException) {
            return false;
        }
        return root instanceof RetriableException
                || root instanceof TimeoutException
                || root instanceof IOException
                || root instanceof InterruptedException;
    }

    public String errorType(Throwable failure) {
        Throwable root = rootCause(failure);
        String simpleName = root.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank()
                ? root.getClass().getName()
                : simpleName;
    }

    public String sanitizedMessage(Throwable failure) {
        Throwable root = rootCause(failure);
        if (root instanceof InterruptedException) {
            return "Kafka publication interrupted";
        }
        if (root instanceof JsonProcessingException || root instanceof SerializationException) {
            return "Non-retriable event serialization failure";
        }
        if (isRetriable(root)) {
            return "Transient Kafka publication failure";
        }
        return "Non-retriable publication failure";
    }

    public Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

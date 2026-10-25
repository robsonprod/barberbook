package io.barberbook.shared.web;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Traduz erros para RFC 9457 Problem Details com {@code type} estável e {@code code} legível. */
@RestControllerAdvice
class ProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException ex) {
        if (ex.type().status() >= 409) {
            // Conflitos são esperados (ex.: corrida pelo mesmo horário); log sem dados do cliente.
            log.info("Request rejected: {}", ex.type().code());
        }
        return problem(ex.type(), ex.getMessage(), ex.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return problem(ProblemType.VALIDATION, "One or more fields are invalid", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParameters(HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> errors.putIfAbsent(
                r.getMethodParameter().getParameterName(),
                r.getResolvableErrors().getFirst().getDefaultMessage()));
        return problem(ProblemType.VALIDATION, "One or more parameters are invalid", errors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException ex) {
        return problem(ProblemType.VALIDATION, "Missing required header " + ex.getHeaderName(),
                Map.of(ex.getHeaderName(), "is required"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missingParameter(MissingServletRequestParameterException ex) {
        return problem(ProblemType.VALIDATION, "Missing required parameter " + ex.getParameterName(),
                Map.of(ex.getParameterName(), "is required"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(ProblemType.VALIDATION, "Invalid value for " + ex.getName(),
                Map.of(ex.getName(), "has an invalid format"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException ex) {
        return problem(ProblemType.VALIDATION, "Malformed request body", Map.of());
    }

    static ProblemDetail problem(ProblemType type, String detail, Map<String, String> fieldErrors) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(type.status()), detail);
        pd.setType(URI.create("/problems/" + type.code()));
        pd.setTitle(type.title());
        pd.setProperty("code", type.code());
        if (!fieldErrors.isEmpty()) {
            pd.setProperty("errors", fieldErrors);
        }
        var correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        return pd;
    }
}

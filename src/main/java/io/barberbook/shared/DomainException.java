package io.barberbook.shared;

import java.util.Map;

/**
 * Erro de negócio com um tipo estável. O handler HTTP traduz para Problem Details;
 * o domínio não conhece HTTP.
 */
public class DomainException extends RuntimeException {

    private final ProblemType type;
    private final Map<String, String> fieldErrors;

    public DomainException(ProblemType type, String detail) {
        this(type, detail, Map.of());
    }

    public DomainException(ProblemType type, String detail, Map<String, String> fieldErrors) {
        super(detail);
        this.type = type;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public ProblemType type() {
        return type;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    public static DomainException validation(String detail) {
        return new DomainException(ProblemType.VALIDATION, detail);
    }

    public static DomainException notFound(String detail) {
        return new DomainException(ProblemType.NOT_FOUND, detail);
    }
}

package io.barberbook.booking.domain;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dados do cliente. São dados pessoais: {@link #toString()} nunca os expõe,
 * então um log descuidado ({@code log.info("{}", booking)}) não vaza nada.
 */
public record Customer(String name, String email, String phone) {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{10,15}$");

    public Customer {
        Map<String, String> errors = new LinkedHashMap<>();
        name = name == null ? "" : name.strip();
        email = email == null ? "" : email.strip();
        phone = phone == null ? "" : phone.replaceAll("[\\s()-]", "");

        if (name.isEmpty() || name.length() > 100) {
            errors.put("customer.name", "must have 1 to 100 characters");
        }
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            errors.put("customer.email", "must be a valid email address");
        }
        if (!PHONE.matcher(phone).matches()) {
            errors.put("customer.phone", "must have 10 to 15 digits, optionally starting with +");
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ProblemType.VALIDATION, "Invalid customer data", errors);
        }
    }

    @Override
    public String toString() {
        return "Customer[redacted]";
    }
}

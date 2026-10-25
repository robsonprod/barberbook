package io.barberbook.shared.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.barberbook.shared.DomainException;
import io.barberbook.shared.ProblemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ProblemDetailsHandlerTest.ThrowingController.class)
@Import({ProblemDetailsHandler.class, CorrelationIdFilter.class, ProblemDetailsHandlerTest.ThrowingController.class})
class ProblemDetailsHandlerTest {

    @Autowired
    MockMvc mvc;

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void everyDomainProblemTypeHasStableTypeCodeAndStatus(ProblemType type) throws Exception {
        mvc.perform(get("/test/throw/{type}", type.name()))
                .andExpect(status().is(type.status()))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("/problems/" + type.code()))
                .andExpect(jsonPath("$.code").value(type.code()))
                .andExpect(jsonPath("$.title").value(type.title()))
                .andExpect(jsonPath("$.detail").value("boom"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void beanValidationErrorsListTheFields() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void missingHeaderIsAValidationError() throws Exception {
        mvc.perform(get("/test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.errors.Idempotency-Key").value("is required"));
    }

    @Test
    void malformedJsonIsAValidationError() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"));
    }

    // --- task 2.3: correlation id ---

    @Test
    void echoesAValidIncomingRequestId() throws Exception {
        mvc.perform(get("/test/throw/NOT_FOUND").header("X-Request-Id", "abc-123"))
                .andExpect(header().string("X-Request-Id", "abc-123"))
                .andExpect(jsonPath("$.correlationId").value("abc-123"));
    }

    @Test
    void generatesAnIdWhenAbsent() throws Exception {
        mvc.perform(get("/test/throw/NOT_FOUND"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void replacesAnUnsafeIncomingId() throws Exception {
        // texto com quebra de linha tentaria forjar uma linha de log
        mvc.perform(get("/test/throw/NOT_FOUND").header("X-Request-Id", "x\nFAKE LOG LINE"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @RestController
    static class ThrowingController {

        record Body(@NotBlank String name) {
        }

        @GetMapping("/test/throw/{type}")
        void fail(@PathVariable ProblemType type) {
            throw new DomainException(type, "boom");
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/test/header")
        void header(@RequestHeader("Idempotency-Key") String key) {
        }
    }
}

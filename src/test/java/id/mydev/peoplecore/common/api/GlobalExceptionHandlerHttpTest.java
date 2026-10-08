package id.mydev.peoplecore.common.api;

import id.mydev.peoplecore.common.command.CommandConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import id.mydev.peoplecore.common.security.RequestCorrelationFilter;

class GlobalExceptionHandlerHttpTest {

    public record DummyRequest(
        @NotBlank(message = "nama tidak boleh kosong")
        String name
    ) {}

    @RestController
    static class TestController {

        @PostMapping("/test/command")
        public String executeCommand() {
            throw new CommandConflictException("Kunci konflik");
        }

        @PostMapping(value = "/test/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
        public String validate(@Valid @RequestBody DummyRequest request) {
            return "OK";
        }

        @GetMapping("/test/param")
        public String param(@RequestParam("requiredField") String requiredField) {
            return requiredField;
        }

        @GetMapping("/test/type-param")
        public int typeParam(@RequestParam("age") int age) {
            return age;
        }

        @GetMapping("/test/method-validation")
        public int methodValidation(@RequestParam("age") @Min(1) int age) { return age; }

        @GetMapping(value = "/test/json", produces = MediaType.APPLICATION_JSON_VALUE)
        public EmployeeResponse employee() {
            return new EmployeeResponse(new EmployeeData("employee-id", "Budi"), new EmptyMetadata());
        }

        record EmployeeData(String id, String name) { }
        record EmptyMetadata() { }
        record EmployeeResponse(EmployeeData data, EmptyMetadata metadata) { }

        @GetMapping("/test/access-denied")
        public String accessDenied() {
            throw new AccessDeniedException("Forbidden");
        }

        @GetMapping("/test/unauthorized")
        public String unauthorized() {
            throw new BadCredentialsException("Bad credentials");
        }

        @GetMapping("/test/response-status")
        public String responseStatus() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item tidak ditemukan");
        }

        @GetMapping("/test/declared-error")
        public String declaredError() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Internal database endpoint is unavailable") {
                @Override
                public HttpHeaders getHeaders() {
                    var headers = new HttpHeaders();
                    headers.set("Retry-After", "3");
                    return headers;
                }
            };
        }

        @GetMapping("/test/server-error")
        public String serverError() {
            throw new RuntimeException("Unexpected internal DB drop");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestCorrelationFilter())
            .build();
    }

    @Test
    void returns409ForCommandConflict() throws Exception {
        mockMvc.perform(post("/test/command"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.key", is("COMMAND_CONFLICT")))
            .andExpect(jsonPath("$.message", is("Kunci permintaan sudah digunakan untuk payload berbeda.")));
    }

    @Test
    void returns403ForAccessDenied() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.key", is("ACCESS_DENIED")))
            .andExpect(jsonPath("$.message", is("Akses ke sumber daya ditolak.")));
    }

    @Test
    void returns401ForUnauthorized() throws Exception {
        mockMvc.perform(get("/test/unauthorized"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.key", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Kredensial tidak valid atau tidak ditemukan.")));
    }

    @Test
    void returns400ForTypeMismatch() throws Exception {
        mockMvc.perform(get("/test/type-param").param("age", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key", is("INVALID_PARAMETER_TYPE")))
            .andExpect(jsonPath("$.message", is("Tipe nilai untuk parameter 'age' tidak valid.")));
    }

    @Test
    void returns400ForValidationError() throws Exception {
        mockMvc.perform(post("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key", is("VALIDATION_ERROR")))
            .andExpect(jsonPath("$.details[0].field", is("name")))
            .andExpect(jsonPath("$.details[0].message", is("nama tidak boleh kosong")));
    }

    @Test
    void returns400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key", is("MALFORMED_JSON")))
            .andExpect(jsonPath("$.message", is("Format data permintaan tidak valid.")));
    }

    @Test
    void returns400ForMissingParameter() throws Exception {
        mockMvc.perform(get("/test/param"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key", is("MISSING_PARAMETER")))
            .andExpect(jsonPath("$.message", is("Parameter wajib 'requiredField' tidak ditemukan.")));
    }

    @Test
    void returns405ForMethodNotAllowedWithAllowHeader() throws Exception {
        mockMvc.perform(get("/test/command"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", notNullValue()))
            .andExpect(header().string("Allow", containsString("POST")))
            .andExpect(jsonPath("$.key", is("METHOD_NOT_ALLOWED")))
            .andExpect(jsonPath("$.message", is("Metode HTTP 'GET' tidak didukung untuk endpoint ini.")));
    }

    @Test
    void returns415ForUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/test/validate")
                .contentType(MediaType.TEXT_PLAIN)
                .content("plain text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.key", is("UNSUPPORTED_MEDIA_TYPE")))
            .andExpect(jsonPath("$.message", is("Tipe media permintaan tidak didukung.")));
    }

    @Test
    void returnsCustomStatusForResponseStatusException() throws Exception {
        mockMvc.perform(get("/test/response-status"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.key", is("NOT_FOUND")))
            .andExpect(jsonPath("$.message", is("Permintaan tidak dapat diproses.")));
    }

    @Test
    void returns500ForGenericServerError() throws Exception {
        mockMvc.perform(get("/test/server-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.key", is("INTERNAL_SERVER_ERROR")))
            .andExpect(jsonPath("$.message", is("Terjadi kegagalan internal pada server.")));
    }
    @Test
    void methodValidationUsesApplicationErrorContract() throws Exception {
        mockMvc.perform(get("/test/method-validation").param("age", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.key", is("VALIDATION_ERROR")))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void mediaNegotiationRetains406AndApplicationError() throws Exception {
        mockMvc.perform(get("/test/json").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isNotAcceptable())
            .andExpect(jsonPath("$.key", is("NOT_ACCEPTABLE")))
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void successUsesTypedDataAndMetadataAndNoObsoleteFields() throws Exception {
        mockMvc.perform(get("/test/json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", is("employee-id")))
            .andExpect(jsonPath("$.metadata").isMap())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(header().string("X-Request-Id", notNullValue()));
    }

    @Test
    void declaredServerErrorsPreserveHeadersButNeverExposeInternalReasons() throws Exception {
        mockMvc.perform(get("/test/declared-error"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "3"))
            .andExpect(jsonPath("$.key", is("SERVICE_UNAVAILABLE")))
            .andExpect(jsonPath("$.message", is("Terjadi kegagalan internal pada server.")));
    }

    @Test
    void errorsOmitInapplicableDetails() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.details").doesNotExist());
    }
}

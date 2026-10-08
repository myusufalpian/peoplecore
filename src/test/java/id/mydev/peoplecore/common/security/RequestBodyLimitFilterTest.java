package id.mydev.peoplecore.common.security;

import id.mydev.peoplecore.common.api.PayloadLimits;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBodyLimitFilterTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLargeBodiesWithOrWithoutContentLength(boolean chunked) throws Exception {
        var request = request(chunked);
        request.setContent(new byte[PayloadLimits.MAX_BODY_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new RequestBodyLimitFilter(new ObjectMapper()).doFilter(request, response, (req, res) -> called.set(true));
        assertFalse(called.get());
        assertEquals(413, response.getStatus());
        assertEquals("PAYLOAD_TOO_LARGE", new ObjectMapper().readTree(response.getContentAsString()).get("key").asString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passesBoundedBodyUnchangedToController(boolean reader) throws Exception {
        var request = request(true);
        request.setContent("{\"name\":\"Ayu\"}".getBytes(StandardCharsets.UTF_8));
        var called = new AtomicBoolean();
        new RequestBodyLimitFilter(new ObjectMapper()).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            called.set(true);
            String content = reader ? req.getReader().readLine()
                : new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("{\"name\":\"Ayu\"}", content);
        });
        assertTrue(called.get());
    }

    @Test
    void leavesFormParsingToServletAndAcceptsJsonSuffixAndExactBoundary() throws Exception {
        var filter = new RequestBodyLimitFilter(new ObjectMapper());
        for (String type : new String[] {"application/x-www-form-urlencoded", ""}) {
            var form = new MockHttpServletRequest();
            if (!type.isEmpty()) { form.setContentType(type); }
            form.setParameter("_csrf", "token");
            filter.doFilter(form, new MockHttpServletResponse(), (req, res) -> assertEquals("token", req.getParameter("_csrf")));
        }
        var request = request(true);
        request.setContentType("application/problem+json; charset=UTF-8");
        request.setCharacterEncoding("UTF-8");
        request.setContent(new byte[PayloadLimits.MAX_BODY_BYTES]);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var input = (jakarta.servlet.ServletInputStream) req.getInputStream();
            assertFalse(input.isFinished());
            assertEquals(PayloadLimits.MAX_BODY_BYTES, input.readAllBytes().length);
            assertTrue(input.isFinished());
        });
    }

    private static MockHttpServletRequest request(boolean chunked) {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return chunked ? -1 : super.getContentLengthLong(); }
        };
        request.setContentType("application/json");
        return request;
    }
}

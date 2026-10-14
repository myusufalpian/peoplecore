package id.mydev.peoplecore.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class UnknownPropertyRejectionTest {

    record Sample(String name) { }

    @Autowired ObjectMapper mapper;

    @Test
    void unknownJsonPropertiesAreRejected() {
        assertThrows(Exception.class,
            () -> mapper.readValue("{\"name\":\"a\",\"unknown\":\"b\"}", Sample.class));
    }
}

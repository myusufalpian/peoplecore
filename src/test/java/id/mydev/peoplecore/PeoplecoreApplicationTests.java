package id.mydev.peoplecore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.Mockito.mockStatic;

@SpringBootTest
@ActiveProfiles("test")
class PeoplecoreApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void startsApplication() {
		String[] arguments = new String[0];
		try (var springApplication = mockStatic(org.springframework.boot.SpringApplication.class)) {
			PeoplecoreApplication.main(arguments);
			springApplication.verify(() -> org.springframework.boot.SpringApplication.run(PeoplecoreApplication.class, arguments));
		}
	}

}

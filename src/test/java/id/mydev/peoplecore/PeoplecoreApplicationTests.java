package id.mydev.peoplecore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.Mockito.mockStatic;
import org.springframework.boot.SpringApplication;

@SpringBootTest
@ActiveProfiles("test")
class PeoplecoreApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void startsApplication() {
		String[] arguments = new String[0];
		try (var springApplication = mockStatic(SpringApplication.class)) {
			PeoplecoreApplication.main(arguments);
			springApplication.verify(() -> SpringApplication.run(PeoplecoreApplication.class, arguments));
		}
	}

}

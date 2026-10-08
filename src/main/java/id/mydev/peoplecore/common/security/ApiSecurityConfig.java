package id.mydev.peoplecore.common.security;

import id.mydev.peoplecore.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiSecurityConfig {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper, RequestBodyLimitFilter bodyFilter) throws Exception {
        var entryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Peoplecore\"");
            writeError(response, mapper, 401, "UNAUTHORIZED", "Kredensial tidak valid atau tidak ditemukan.");
        };
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
            .addFilterAfter(bodyFilter, AuthorizationFilter.class)
            .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)
                .accessDeniedHandler((request, response, ex) ->
                    writeError(response, mapper, 403, "ACCESS_DENIED", "Akses ke sumber daya ditolak.")));
        return http.build();
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> bodyFilterRegistration(RequestBodyLimitFilter bodyFilter) {
        var registration = new FilterRegistrationBean<>(bodyFilter);
        registration.setEnabled(false);
        return registration;
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status,
                                   String key, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ErrorResponse.of(key, message));
    }
}

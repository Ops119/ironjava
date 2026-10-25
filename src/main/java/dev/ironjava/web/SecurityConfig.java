package dev.ironjava.web;

import dev.ironjava.config.IronJavaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {
    public static final String BEARER_ATTRIBUTE = "ironjava.bearer";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IronJavaProperties properties) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login", "/css/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll().defaultSuccessUrl("/dashboard", true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(apiOrLoginEntryPoint()))
                .addFilterBefore(new BearerTokenFilter(properties), CsrfFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers(request ->
                        Boolean.TRUE.equals(request.getAttribute(BEARER_ATTRIBUTE))));
        return http.build();
    }

    /**
     * Spring Security 7 uses an explicit {@code authenticationEntryPoint} as-is and does not
     * consult matchers registered with {@code defaultAuthenticationEntryPointFor}. Branch here
     * so API and actuator calls get 401 while browser pages still redirect to the login form.
     */
    private static AuthenticationEntryPoint apiOrLoginEntryPoint() {
        AuthenticationEntryPoint login = new LoginUrlAuthenticationEntryPoint("/login");
        AuthenticationEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        return (request, response, authException) -> {
            String uri = request.getRequestURI();
            if (uri.startsWith("/api/") || uri.startsWith("/actuator/")) {
                unauthorized.commence(request, response, authException);
                return;
            }
            login.commence(request, response, authException);
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(IronJavaProperties properties, PasswordEncoder encoder) {
        String password = properties.getAuth().getPassword();
        if (password == null || password.isBlank()) {
            password = "worker-profile-has-no-dashboard-password";
        }
        return new InMemoryUserDetailsManager(User.withUsername(properties.getAuth().getUsername())
                .password(encoder.encode(password))
                .roles("USER")
                .build());
    }

    static final class BearerTokenFilter extends OncePerRequestFilter {
        private final IronJavaProperties properties;

        BearerTokenFilter(IronJavaProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            String configured = properties.getAuth().getApiToken();
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    && configured != null && !configured.isBlank()) {
                String presented = header.substring(7).trim();
                if (presented.equals(configured)) {
                    request.setAttribute(BEARER_ATTRIBUTE, Boolean.TRUE);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            properties.getAuth().getUsername(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                }
            }
            chain.doFilter(request, response);
        }
    }
}

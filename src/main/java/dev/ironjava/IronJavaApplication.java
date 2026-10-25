package dev.ironjava;

import dev.ironjava.config.IronJavaProperties;
import dev.ironjava.domain.Domain.Role;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(IronJavaProperties.class)
public class IronJavaApplication {
    public static void main(String[] args) {
        SpringApplication.run(IronJavaApplication.class, args);
    }

    @Bean
    ApplicationRunner requireDashboardPassword(IronJavaProperties properties) {
        return (ApplicationArguments args) -> {
            if (properties.getRole() == Role.WORKER) {
                return;
            }
            String password = properties.getAuth().getPassword();
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "IRONJAVA_AUTH_PASSWORD is required when ironjava.role is CONTROL or ALL");
            }
        };
    }
}

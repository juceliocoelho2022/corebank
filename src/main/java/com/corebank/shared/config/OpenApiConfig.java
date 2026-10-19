package com.corebank.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI coreBankOpenApi() {
        return new OpenAPI().info(new Info()
                .title("CoreBank API")
                .version("0.8.0")
                .description("Banking as a Service portfolio backend"));
    }
}

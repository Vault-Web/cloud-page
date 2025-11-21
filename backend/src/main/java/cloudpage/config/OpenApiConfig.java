package cloudpage.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for OpenAPI / Swagger documentation.
 *
 * <p>Sets up JWT bearer authentication for the API documentation, allowing secured endpoints to be
 * tested via Swagger UI.
 */
@Configuration
public class OpenApiConfig {

  /**
   * Creates a custom OpenAPI instance with JWT bearer authentication configured.
   *
   * @return the configured OpenAPI object
   */
  @Bean
  public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}

package cloudpage.config;

import cloudpage.security.PublicPaths;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
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
        .info(new Info().title("Cloud Page API").version("v1"))
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

  @Bean
  public OpenApiCustomizer documentPublicRoutes() {
    return api ->
        api.getPaths()
            .forEach(
                (path, item) -> {
                  if (PublicPaths.PREFIXES.stream().anyMatch(path::startsWith)) {
                    item.readOperations().forEach(operation -> operation.setSecurity(List.of()));
                  }
                });
  }
}

package cloudpage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cloud_openapi",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "server.ssl.enabled=false",
      "jwt.secret=openapi-test-fixture-key-not-for-production",
      "cloudpage.virus-scan.enabled=false",
      "springdoc.api-docs.enabled=true"
    })
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path CONTRACT = Path.of("openapi/cloud-page-v1.json");

  @Autowired private MockMvc mockMvc;

  @Test
  void exportsTheVersionedConsumerContract() throws Exception {
    byte[] response =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    ObjectNode spec = (ObjectNode) JSON.readTree(response);
    spec.putArray("servers").addObject().put("url", "/");

    verifyFolderContract(spec);
    String generated =
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(ordered(spec)) + "\n";
    if (Boolean.getBoolean("cloud.openapi.write")) {
      Files.createDirectories(CONTRACT.getParent());
      Files.writeString(CONTRACT, generated);
    } else {
      assertThat(Files.readString(CONTRACT))
          .as(
              "Cloud Page contract. Refresh with ./mvnw -Dtest=OpenApiContractTest -Dcloud.openapi.write=true test")
          .isEqualTo(generated);
    }
  }

  private void verifyFolderContract(JsonNode spec) {
    assertThat(spec.path("openapi").asText()).startsWith("3.");
    assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText())
        .isEqualTo("bearer");
    assertThat(spec.at("/security/0").has("bearerAuth")).isTrue();
    JsonNode listing = spec.at("/paths/~1api~1folders~1content/get");
    assertThat(listing.path("operationId").asText()).isEqualTo("getFolderContent");
    assertThat(listing.path("responses").has("401")).isTrue();
    assertThat(listing.path("responses").has("400")).isTrue();
    assertThat(listing.path("responses").has("429")).isTrue();
    assertThat(listing.path("parameters"))
        .anySatisfy(
            parameter -> {
              assertThat(parameter.path("name").asText()).isEqualTo("page");
              assertThat(parameter.path("required").asBoolean()).isTrue();
              assertThat(parameter.at("/schema/minimum").asInt(-1)).isZero();
              assertThat(parameter.at("/schema/type").asText()).isEqualTo("integer");
            });
    String pageRef = listing.at("/responses/200/content/application~1json/schema/$ref").asText();
    assertThat(pageRef).startsWith("#/components/schemas/");
    assertThat(spec.at(pageRef.substring(1)).path("properties").has("totalElements")).isTrue();
    assertThat(spec.at(pageRef.substring(1)).path("required"))
        .anySatisfy(field -> assertThat(field.asText()).isEqualTo("content"));
    JsonNode edit = spec.at("/paths/~1api~1shares~1{id}~1edit/put");
    assertThat(edit.path("parameters"))
        .anySatisfy(field -> assertThat(field.path("name").asText()).isEqualTo("If-Match"));
    assertThat(
            edit.at("/requestBody/content/multipart~1form-data/schema/properties/file/format")
                .asText())
        .isEqualTo("binary");
    assertThat(
            spec.at(
                    "/paths/~1api~1folders~1download/get/responses/200/content/application~1zip/schema/format")
                .asText())
        .isEqualTo("binary");
  }

  private JsonNode ordered(JsonNode node) {
    if (node.isObject()) {
      var fields = new TreeMap<String, JsonNode>();
      node.fields()
          .forEachRemaining(field -> fields.put(field.getKey(), ordered(field.getValue())));
      return JSON.createObjectNode().setAll(fields);
    }
    if (node.isArray()) {
      var result = JSON.createArrayNode();
      node.forEach(item -> result.add(ordered(item)));
      return result;
    }
    return node;
  }
}

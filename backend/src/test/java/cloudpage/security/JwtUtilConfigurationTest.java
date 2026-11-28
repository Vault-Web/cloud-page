package cloudpage.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JwtUtilConfigurationTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "too-short"})
  void startupRejectsMissingOrWeakSigningConfiguration(String secret) {
    assertThatThrownBy(() -> new JwtUtil(secret))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void acceptsAConfiguredSigningKey() {
    assertDoesNotThrow(() -> new JwtUtil("a".repeat(32)));
  }
}

package io.plainround.seatwave.ratelimit.issuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key ring refuses, at start-up and with a reason, every key file the issuer cannot use. */
class KeyRingTest {

  private static final int RSA_BITS = 2048;

  @TempDir Path folder;

  @Test
  void signsWithTheFirstKeyAndPublishesOnlyPublicHalves() throws Exception {
    ECKey first = p256("key-1");
    ECKey second = p256("key-2");

    KeyRing ring = ringFor(List.of(first, second));

    assertThat(ring.signingKey().getKeyID()).isEqualTo("key-1");
    assertThat(ring.publicKeys().getKeys())
        .extracting(JWK::getKeyID)
        .containsExactly("key-1", "key-2");
    assertThat(ring.publicKeys().getKeys()).noneMatch(JWK::isPrivate);
  }

  @Test
  void refusesAMissingKeyFileAndSaysHowToMakeOne() {
    assertThatThrownBy(() -> new KeyRing(settings(folder.resolve("absent.json"))))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("cannot be read; run ./gradlew :issuer:keys");
  }

  @Test
  void refusesAFileThatIsNotAKeySet() throws Exception {
    Path file = folder.resolve("keys.json");
    Files.writeString(file, "not json", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> new KeyRing(settings(file)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not a JWK set");
  }

  @Test
  void refusesAnEmptyKeySet() {
    assertThatThrownBy(() -> ringFor(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("holds no key");
  }

  @Test
  void refusesAPublicKeyThatCannotSign() {
    assertThatThrownBy(() -> ringFor(List.of(p256("key-1").toPublicJWK())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be a private P-256 key with a key id");
  }

  @Test
  void refusesAKeyWithoutAKeyId() {
    assertThatThrownBy(() -> ringFor(List.of(new ECKeyGenerator(Curve.P_256).generate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be a private P-256 key with a key id");
  }

  @Test
  void refusesAnEllipticCurveKeyOnAnotherCurve() {
    assertThatThrownBy(
            () -> ringFor(List.of(new ECKeyGenerator(Curve.P_384).keyID("key-384").generate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be a private P-256 key with a key id");
  }

  @Test
  void refusesAnRsaKey() {
    assertThatThrownBy(
            () -> ringFor(List.of(new RSAKeyGenerator(RSA_BITS).keyID("key-rsa").generate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be a private P-256 key with a key id");
  }

  private KeyRing ringFor(List<JWK> keys) throws Exception {
    Path file = folder.resolve("issuer-keys.json");
    Files.writeString(file, new JWKSet(keys).toString(false), StandardCharsets.UTF_8);
    return new KeyRing(settings(file));
  }

  private static ECKey p256(String keyId) throws Exception {
    return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
  }

  private static IssuerProperties settings(Path keyFile) {
    return new IssuerProperties(
        "http://issuer:8080", "seatwave-api", Duration.ofMinutes(15), keyFile);
  }
}

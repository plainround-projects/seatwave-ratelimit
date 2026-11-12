package io.plainround.seatwave.ratelimit.issuer;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key file: one private P-256 key, owner-only, and a rotation that keeps one previous key. */
class KeyRingCliTest {

  @TempDir Path folder;

  @Test
  void generatesOnePrivateKeyReadableByItsOwnerAlone() throws Exception {
    Path file = folder.resolve("keys/issuer-keys.json");

    KeyRingCli.main(new String[] {"generate", file.toString()});

    List<JWK> keys = JWKSet.load(file.toFile()).getKeys();
    assertThat(keys).singleElement().satisfies(key -> assertThat(key.isPrivate()).isTrue());
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        .isEqualTo("rw-------");
  }

  @Test
  void rotatesANewKeyToTheFrontAndKeepsThePreviousOne() throws Exception {
    Path file = folder.resolve("issuer-keys.json");
    KeyRingCli.main(new String[] {"generate", file.toString()});
    String first = JWKSet.load(file.toFile()).getKeys().getFirst().getKeyID();

    KeyRingCli.main(new String[] {"rotate", file.toString()});

    List<JWK> keys = JWKSet.load(file.toFile()).getKeys();
    assertThat(keys).hasSize(2);
    assertThat(keys.get(0).getKeyID()).isNotEqualTo(first);
    assertThat(keys.get(1).getKeyID()).isEqualTo(first);
  }

  @Test
  void dropsTheOldestKeyOnASecondRotation() throws Exception {
    Path file = folder.resolve("issuer-keys.json");
    KeyRingCli.main(new String[] {"generate", file.toString()});
    String oldest = JWKSet.load(file.toFile()).getKeys().getFirst().getKeyID();
    KeyRingCli.main(new String[] {"rotate", file.toString()});

    KeyRingCli.main(new String[] {"rotate", file.toString()});

    assertThat(JWKSet.load(file.toFile()).getKeys())
        .hasSize(2)
        .extracting(JWK::getKeyID)
        .doesNotContain(oldest);
  }
}

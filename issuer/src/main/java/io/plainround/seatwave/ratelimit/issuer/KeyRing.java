package io.plainround.seatwave.ratelimit.issuer;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The issuer's keys, read once from the key file at start-up. The first key signs; every key's
 * public half is published, so tokens signed with the previous key stay valid while it is still
 * listed (key rotation). Immutable after construction, so thread-safe.
 */
@Component
public final class KeyRing {

  private final ECKey signingKey;
  private final JWKSet publicKeys;

  /**
   * Loads and checks the key file.
   *
   * @param settings where the key file is
   * @throws IllegalStateException when the file holds no key, or a key that is not a private P-256
   *     key with an id; the message says which
   */
  public KeyRing(IssuerProperties settings) {
    List<JWK> keys;
    try {
      keys = JWKSet.load(settings.keyFile().toFile()).getKeys();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "the key file " + settings.keyFile() + " cannot be read; run ./gradlew :issuer:keys",
          unreadable);
    } catch (ParseException malformed) {
      throw new IllegalStateException(
          "the key file " + settings.keyFile() + " is not a JWK set: " + malformed.getMessage(),
          malformed);
    }
    if (keys.isEmpty()) {
      throw new IllegalStateException("the key file " + settings.keyFile() + " holds no key");
    }
    for (JWK key : keys) {
      // ES256 is defined on P-256 only (RFC 7518): a key on another curve could not sign a token
      // the gateway accepts, so it is refused here, at start-up, not on the first request.
      if (!(key instanceof ECKey ecKey)
          || !Curve.P_256.equals(ecKey.getCurve())
          || !ecKey.isPrivate()
          || key.getKeyID() == null) {
        throw new IllegalStateException(
            "every key in " + settings.keyFile() + " must be a private P-256 key with a key id");
      }
    }
    this.signingKey = (ECKey) keys.getFirst();
    this.publicKeys = new JWKSet(keys).toPublicJWKSet();
  }

  /**
   * Returns the key that signs new tokens.
   *
   * @return the first key of the file
   */
  public ECKey signingKey() {
    return signingKey;
  }

  /**
   * Returns the public halves of every key, as {@code /.well-known/jwks.json} serves them.
   *
   * @return the public key set
   */
  public JWKSet publicKeys() {
    return publicKeys;
  }
}

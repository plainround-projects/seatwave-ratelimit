package io.plainround.seatwave.ratelimit.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * The header rules refuse a token before the verifier sees it; the verifier's own failures leave
 * with the right reason. A counting stand-in verifier proves the order.
 */
class CheckedJwtDecoderTest {

  private static final JWTClaimsSet CLAIMS = new JWTClaimsSet.Builder().subject("fan-42").build();

  private final AtomicInteger verifierCalls = new AtomicInteger();

  @Test
  void refusesAnUnsignedTokenWithoutCallingTheVerifier() {
    String token = new PlainJWT(CLAIMS).serialize();

    assertRefused(decoder(any -> fail()), token, TokenRefusal.UNSIGNED);
    assertThat(verifierCalls).hasValue(0);
  }

  @Test
  void refusesAnHmacTokenKeyedWithThePublicKeyWithoutCallingTheVerifier() throws JOSEException {
    ECKey issuerKey = new ECKeyGenerator(Curve.P_256).keyID("issuer-key-1").generate();
    // The algorithm-confusion attack: the public key is public, so anyone can compute this MAC.
    byte[] publicKeyBytes = issuerKey.toECPublicKey().getEncoded();
    SignedJWT forged =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("issuer-key-1").build(), CLAIMS);
    forged.sign(new MACSigner(publicKeyBytes));

    assertRefused(decoder(any -> fail()), forged.serialize(), TokenRefusal.ALGORITHM);
    assertThat(verifierCalls).hasValue(0);
  }

  @Test
  void refusesAnEs256TokenWithoutAKeyId() throws JOSEException {
    SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), CLAIMS);
    token.sign(new ECDSASigner(new ECKeyGenerator(Curve.P_256).generate()));

    assertRefused(decoder(any -> fail()), token.serialize(), TokenRefusal.MALFORMED);
  }

  @Test
  void refusesTextThatIsNotAToken() {
    assertRefused(decoder(any -> fail()), "not-a-token", TokenRefusal.MALFORMED);
  }

  @Test
  void reportsABadSignatureFromTheVerifierAsSignature() throws JOSEException {
    JwtDecoder decoder =
        decoder(any -> {
          throw new BadJwtException("Signed JWT rejected: Invalid signature");
        });

    assertRefused(decoder, es256(), TokenRefusal.SIGNATURE);
  }

  @Test
  void reportsAClaimRuleFailureWithItsOwnReason() throws JOSEException {
    JwtDecoder decoder =
        decoder(any -> {
          throw new JwtValidationException(
              "invalid", List.of(new OAuth2Error("invalid_token", "expired", "")));
        });

    assertRefused(decoder, es256(), TokenRefusal.EXPIRED);
  }

  @Test
  void reportsAnUnreachableKeySetAsKeySetUnavailable() throws JOSEException {
    JwtDecoder decoder =
        decoder(any -> {
          throw new JwtException("Couldn't retrieve remote JWK set");
        });

    assertRefused(decoder, es256(), TokenRefusal.KEY_SET_UNAVAILABLE);
  }

  private JwtDecoder decoder(JwtDecoder verifier) {
    return new CheckedJwtDecoder(token -> {
      verifierCalls.incrementAndGet();
      return verifier.decode(token);
    });
  }

  private static Jwt fail() {
    throw new AssertionError("the verifier must not be called");
  }

  private static String es256() throws JOSEException {
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("issuer-key-1").build(), CLAIMS);
    token.sign(new ECDSASigner(new ECKeyGenerator(Curve.P_256).generate()));
    return token.serialize();
  }

  private static void assertRefused(JwtDecoder decoder, String token, TokenRefusal expected) {
    assertThatThrownBy(() -> decoder.decode(token))
        .isInstanceOfSatisfying(
            RefusedTokenException.class,
            refused -> assertThat(refused.refusal()).isEqualTo(expected));
  }
}

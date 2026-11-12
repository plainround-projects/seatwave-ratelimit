package io.plainround.seatwave.ratelimit.edge;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * The gateway's one token decoder: it reads the header first and refuses anything that is not an
 * ES256 token with a key id, then lets Spring Security's Nimbus decoder verify the signature
 * against the issuer's key set and run {@link TokenClaimsRules}. Every refusal leaves as a {@link
 * RefusedTokenException} with exactly one reason.
 *
 * <p>The header check duplicates a refusal the Nimbus decoder also makes (it is built for ES256
 * only). It is kept on purpose: the allowed algorithm is then a rule in this repository, tested
 * here, and the metric can tell "wrong algorithm" from "bad signature". Thread-safe: the delegate
 * is thread-safe and nothing else is stored.
 */
public final class CheckedJwtDecoder implements JwtDecoder {

  // The one algorithm the issuer signs with: ECDSA on P-256 with SHA-256 (RFC 7518). Never "none",
  // never an HMAC, whose secret would be the public key in the classic algorithm-confusion attack.
  private static final JWSAlgorithm ALLOWED = JWSAlgorithm.ES256;

  private final JwtDecoder verifier;

  /**
   * Wraps the verifying decoder.
   *
   * @param verifier Spring Security's decoder, built for ES256 against the key set, with {@link
   *     TokenClaimsRules} as its validator
   */
  public CheckedJwtDecoder(JwtDecoder verifier) {
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /**
   * Decodes and checks a token.
   *
   * @param token the text after {@code Bearer }
   * @return the verified token
   * @throws RefusedTokenException for every refusal, with its reason
   */
  @Override
  public Jwt decode(String token) {
    Optional<TokenRefusal> headerRefusal = headerRefusal(token);
    if (headerRefusal.isPresent()) {
      throw new RefusedTokenException(headerRefusal.get(), null);
    }
    try {
      return verifier.decode(token);
    } catch (JwtValidationException invalid) {
      // Our own claim rules put the reason's code into the error's description.
      throw new RefusedTokenException(claimRefusal(invalid), invalid);
    } catch (BadJwtException badSignature) {
      throw new RefusedTokenException(TokenRefusal.SIGNATURE, badSignature);
    } catch (JwtException unavailable) {
      // Not the token's fault: the key set could not be fetched. Refuse anyway (fail safe), but
      // with a reason that tells the client to retry rather than to sign in again.
      throw new RefusedTokenException(TokenRefusal.KEY_SET_UNAVAILABLE, unavailable);
    }
  }

  private static Optional<TokenRefusal> headerRefusal(String token) {
    JWT parsed;
    try {
      parsed = JWTParser.parse(token);
    } catch (ParseException malformed) {
      return Optional.of(TokenRefusal.MALFORMED);
    }
    return switch (parsed) {
      case PlainJWT unsigned -> Optional.of(TokenRefusal.UNSIGNED);
      case SignedJWT signed when !ALLOWED.equals(signed.getHeader().getAlgorithm()) ->
          Optional.of(TokenRefusal.ALGORITHM);
      // The key id picks one key of the set; without it, rotation cannot work.
      case SignedJWT signed when signed.getHeader().getKeyID() == null ->
          Optional.of(TokenRefusal.MALFORMED);
      case SignedJWT signed -> Optional.empty();
      // An encrypted token is not what SeatWave's issuer writes.
      default -> Optional.of(TokenRefusal.MALFORMED);
    };
  }

  private static TokenRefusal claimRefusal(JwtValidationException invalid) {
    return invalid.getErrors().stream()
        .map(error -> error.getDescription())
        .flatMap(code ->
            java.util.Arrays.stream(TokenRefusal.values())
                .filter(refusal -> refusal.code().equals(code)))
        .findFirst()
        .orElse(TokenRefusal.SIGNATURE);
  }
}

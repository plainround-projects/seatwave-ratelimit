package io.plainround.seatwave.ratelimit.issuer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The issuer's two endpoints: {@code GET /.well-known/jwks.json}, the public key set (RFC 7517),
 * and {@code POST /v1/tokens}, which signs a 15-minute token for a fan name. It is a lab stand-in:
 * it checks no password, so Compose publishes it on 127.0.0.1 only.
 */
@RestController
public final class TokenController {

  private static final Pattern SUBJECT = Pattern.compile("fan-[0-9]{1,9}");
  // Gateways may keep the key set for 5 minutes; a rotation keeps the old key listed longer.
  private static final CacheControl KEY_SET_CACHE = CacheControl.maxAge(Duration.ofMinutes(5));

  private final KeyRing keys;
  private final IssuerProperties settings;
  private final Clock clock;
  private final ECDSASigner signer;

  /**
   * Creates the controller.
   *
   * @param keys the signing key and the published keys
   * @param settings the issuer, audience and lifetime
   * @param clock the issuer's clock
   * @throws JOSEException when the signing key cannot sign, which a checked key ring never holds
   */
  public TokenController(KeyRing keys, IssuerProperties settings, Clock clock)
      throws JOSEException {
    this.keys = keys;
    this.settings = settings;
    this.clock = clock;
    this.signer = new ECDSASigner(keys.signingKey());
  }

  /**
   * Publishes the public keys.
   *
   * @return the key set as JSON, cacheable for 5 minutes
   */
  @GetMapping("/.well-known/jwks.json")
  public ResponseEntity<Map<String, Object>> keySet() {
    return ResponseEntity.ok().cacheControl(KEY_SET_CACHE).body(keys.publicKeys().toJSONObject());
  }

  /**
   * Signs one token for a fan name, valid from now for the configured lifetime.
   *
   * @param subject the fan's name, such as {@code fan-42}
   * @return 200 and the token; 400 for a malformed name
   * @throws JOSEException when signing fails, a programming error
   */
  @PostMapping("/v1/tokens")
  public ResponseEntity<?> token(@RequestParam(defaultValue = "") String subject)
      throws JOSEException {
    if (!SUBJECT.matcher(subject).matches()) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "subject must look like fan-42");
      problem.setType(URI.create("https://seatwave.example/problems/invalid-request"));
      return ResponseEntity.of(problem).build();
    }
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(settings.issuer())
            .audience(settings.audience())
            .subject(subject)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plus(settings.tokenLifetime())))
            // A random id per token, so a log line can name one token without holding it.
            .jwtID(UUID.randomUUID().toString())
            .build();
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType.JWT)
            .keyID(keys.signingKey().getKeyID())
            .build();
    SignedJWT token = new SignedJWT(header, claims);
    token.sign(signer);
    return ResponseEntity.ok(
        Map.of(
            "accessToken", token.serialize(),
            "tokenType", "Bearer",
            "expiresIn", settings.tokenLifetime().toSeconds()));
  }
}

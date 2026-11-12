package io.plainround.seatwave.ratelimit.labtools;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Writes JSON Web Tokens with the JDK's own cryptography: ES256 (ECDSA on P-256 with SHA-256, the
 * signature as the 64-byte R and S that RFC 7518 requires), HS256 (an HMAC), and an unsigned {@code
 * alg: none} token. Teaching code; the issuer stand-in signs with Nimbus. Thread-safe: it has no
 * state.
 */
final class TokenMint {

  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private TokenMint() {}

  /**
   * Signs a token with an EC private key.
   *
   * @param headerJson the header, for example {@code {"alg":"ES256","kid":"k1"}}
   * @param claimsJson the claims
   * @param key the P-256 private key
   * @return the compact token: header, claims and signature, base64url, joined by dots
   * @throws GeneralSecurityException when the JDK cannot sign, a setup error
   */
  static String es256(String headerJson, String claimsJson, PrivateKey key)
      throws GeneralSecurityException {
    String signingInput = signingInput(headerJson, claimsJson);
    // "inP1363Format" gives R and S side by side, the form JWS uses, instead of DER.
    Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
    signer.initSign(key);
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + BASE64URL.encodeToString(signer.sign());
  }

  /**
   * Signs a token with an HMAC secret.
   *
   * @param headerJson the header
   * @param claimsJson the claims
   * @param secret the secret's bytes
   * @return the compact token
   * @throws GeneralSecurityException when the JDK cannot compute the HMAC, a setup error
   */
  static String hs256(String headerJson, String claimsJson, byte[] secret)
      throws GeneralSecurityException {
    String signingInput = signingInput(headerJson, claimsJson);
    return signingInput + "." + BASE64URL.encodeToString(hmac(signingInput, secret));
  }

  /**
   * Writes a token with no signature at all, as {@code alg: none} allows.
   *
   * @param claimsJson the claims
   * @return the compact token, ending in a dot and an empty signature
   */
  static String unsigned(String claimsJson) {
    return signingInput("{\"alg\":\"none\"}", claimsJson) + ".";
  }

  /**
   * Computes HMAC-SHA256 over a signing input.
   *
   * @param signingInput the base64url header and claims joined by a dot
   * @param secret the secret's bytes
   * @return the 32-byte MAC
   * @throws GeneralSecurityException when the JDK cannot compute it, a setup error
   */
  static byte[] hmac(String signingInput, byte[] secret) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
  }

  private static String signingInput(String headerJson, String claimsJson) {
    return BASE64URL.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
        + "."
        + BASE64URL.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
  }
}

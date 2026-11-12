package io.plainround.seatwave.ratelimit.issuer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes the issuer's private key set into an ignored folder: {@code generate} makes a new set with
 * one key; {@code rotate} puts a new key first and keeps the previous one, so tokens it signed stay
 * valid until they expire. No key is ever committed.
 */
public final class KeyRingCli {

  // The new signing key and the one before it; an older key's tokens have long expired.
  private static final int KEYS_KEPT = 2;
  private static final int EXIT_USAGE = 2;

  private KeyRingCli() {}

  /**
   * Generates or rotates the key file.
   *
   * @param arguments {@code generate <file>} or {@code rotate <file>}
   * @throws IOException when the file cannot be read or written
   * @throws JOSEException when the JDK cannot make a P-256 key
   * @throws ParseException when the existing file is not a JWK set
   */
  public static void main(String[] arguments) throws IOException, JOSEException, ParseException {
    if (arguments.length != 2 || !List.of("generate", "rotate").contains(arguments[0])) {
      System.err.println("usage: KeyRingCli <generate|rotate> <key file>");
      System.exit(EXIT_USAGE);
    }
    Path file = Path.of(arguments[1]);
    if (arguments[0].equals("rotate") && !Files.isRegularFile(file)) {
      // A rotation keeps the previous key, so there must be one: say so instead of a stack trace.
      System.err.println("rotate: there is no key file at " + file + "; run generate first");
      System.exit(1);
    }
    List<JWK> keys = new ArrayList<>();
    keys.add(new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate());
    if (arguments[0].equals("rotate")) {
      List<JWK> previous = JWKSet.load(file.toFile()).getKeys();
      keys.addAll(previous.subList(0, Math.min(previous.size(), KEYS_KEPT - 1)));
    }
    Files.createDirectories(file.toAbsolutePath().getParent());
    // toString(false) keeps the private parts: this file is the issuer's secret, so only its owner
    // may read it. Docker Desktop on a Mac shows a mounted file as owned by the reading user; on a
    // Linux host the file must also be given to uid 10001, the issuer's user in the image.
    Files.writeString(file, new JWKSet(keys).toString(false), StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    System.out.println(
        arguments[0]
            + ": "
            + keys.size()
            + " key(s), signing key "
            + ((ECKey) keys.getFirst()).getKeyID());
  }
}

package io.plainround.seatwave.ratelimit.labtools;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one top-level string or whole-number field from the flat JSON objects that {@link
 * TokenMint} writes. Teaching code only: the JDK has no JSON parser, and the real gateway reads
 * tokens with Nimbus JOSE + JWT, which parses full JSON. Thread-safe: it has no state.
 */
final class CompactJson {

  private CompactJson() {}

  /**
   * Finds a field's value.
   *
   * @param json a flat JSON object without nested objects or escaped quotes
   * @param name the field's name
   * @return the value as text (a string without its quotes, or the digits), or empty if absent
   */
  static Optional<String> field(String json, String name) {
    Matcher match =
        Pattern.compile("\"" + Pattern.quote(name) + "\":(?:\"([^\"]*)\"|(-?[0-9]+))")
            .matcher(json);
    if (!match.find()) {
      return Optional.empty();
    }
    return Optional.of(match.group(1) != null ? match.group(1) : match.group(2));
  }
}

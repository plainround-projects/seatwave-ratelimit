package io.plainround.seatwave.ratelimit.edge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The request as the routes forward it: without the client's {@code Authorization} header and
 * without any {@code X-SeatWave-} header the client sent, and with {@code X-SeatWave-Subject} set
 * to the verified token's subject when there is one. Every header lookup goes through here, so a
 * client cannot smuggle an identity past it by spelling a header in another case.
 */
final class IdentityRequest extends HttpServletRequestWrapper {

  /** The header that carries the fan's verified identity to the services. */
  static final String SUBJECT_HEADER = "X-SeatWave-Subject";

  // The prefix of every header only the gateway may set.
  private static final String INTERNAL_PREFIX = "x-seatwave-";
  private static final String AUTHORIZATION = "authorization";

  private final Optional<String> subject;

  IdentityRequest(HttpServletRequest request, Optional<String> subject) {
    super(request);
    this.subject = Objects.requireNonNull(subject, "subject");
  }

  @Override
  public String getHeader(String name) {
    List<String> values = Collections.list(getHeaders(name));
    // The servlet contract: null, not an empty text, means "no such header".
    return values.isEmpty() ? null : values.getFirst();
  }

  @Override
  public Enumeration<String> getHeaders(String name) {
    if (SUBJECT_HEADER.equalsIgnoreCase(name)) {
      return Collections.enumeration(subject.stream().toList());
    }
    return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
  }

  @Override
  public Enumeration<String> getHeaderNames() {
    List<String> names = new ArrayList<>();
    for (String name : Collections.list(super.getHeaderNames())) {
      if (!isHidden(name)) {
        names.add(name);
      }
    }
    subject.ifPresent(present -> names.add(SUBJECT_HEADER));
    return Collections.enumeration(names);
  }

  private static boolean isHidden(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.equals(AUTHORIZATION) || lower.startsWith(INTERNAL_PREFIX);
  }
}

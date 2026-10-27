package com.wenqi.fulfillment.order;

/** Safe classification only: upstream bodies and payment tokens never enter logs. */
public class DependencyFailure extends RuntimeException {
  private final String code;
  private final boolean retryable;
  private final int status;

  public DependencyFailure(String code, boolean retryable, int status) {
    super(code);
    this.code = code;
    this.retryable = retryable;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public int status() {
    return status;
  }

  public static DependencyFailure invalid() {
    return new DependencyFailure("INVALID_DEPENDENCY_RESPONSE", false, 502);
  }
}

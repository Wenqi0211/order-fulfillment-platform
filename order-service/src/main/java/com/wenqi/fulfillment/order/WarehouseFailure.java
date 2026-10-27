package com.wenqi.fulfillment.order;

public class WarehouseFailure extends RuntimeException {
  private final String code;
  private final boolean retryable;

  public WarehouseFailure(String code, boolean retryable) {
    super(code);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}

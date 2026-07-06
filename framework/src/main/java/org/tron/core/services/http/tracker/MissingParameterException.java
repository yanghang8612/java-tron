package org.tron.core.services.http.tracker;

public class MissingParameterException extends RuntimeException {

  public MissingParameterException(String name) {
    super("parameter " + name + " is required");
  }
}

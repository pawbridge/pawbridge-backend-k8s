package com.pawbridge.userservice.exception;


import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class InconsistentPasswordException extends ApplicationException {
  private static final ErrorCode ERROR_CODE = ErrorCode.INCONSISTENT_PASSWORD;

  public InconsistentPasswordException(){super(ERROR_CODE);}

}

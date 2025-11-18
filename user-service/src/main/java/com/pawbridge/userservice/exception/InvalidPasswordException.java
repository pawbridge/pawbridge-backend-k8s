package com.pawbridge.userservice.exception;


import com.pawbridge.userservice.exception.common.ApplicationException;
import com.pawbridge.userservice.exception.common.ErrorCode;

public class InvalidPasswordException extends ApplicationException {
  private static final ErrorCode ERROR_CODE = ErrorCode.INVALID_PASSWORD;

  public InvalidPasswordException(){super(ERROR_CODE);}

}

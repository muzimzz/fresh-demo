package com.example.freshdemo.address.exception;

import com.example.freshdemo.common.exception.BusinessException;

public class AddressException extends BusinessException {

    public AddressException(AddressErrorCode errorCode) {
        super(errorCode);
    }

    public AddressException(AddressErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}

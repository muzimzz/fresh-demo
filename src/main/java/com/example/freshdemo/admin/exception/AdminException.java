package com.example.freshdemo.admin.exception;

import com.example.freshdemo.common.exception.BusinessException;

public class AdminException extends BusinessException {

    public AdminException(AdminErrorCode errorCode) {
        super(errorCode);
    }

    public AdminException(AdminErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}

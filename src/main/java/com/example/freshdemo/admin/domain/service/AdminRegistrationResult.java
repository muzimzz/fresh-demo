package com.example.freshdemo.admin.domain.service;

import com.example.freshdemo.admin.domain.entity.Admin;

public record AdminRegistrationResult(Admin admin, String temporaryPassword) {
}

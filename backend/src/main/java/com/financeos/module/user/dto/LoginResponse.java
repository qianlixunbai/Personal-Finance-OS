package com.financeos.module.user.dto;

public record LoginResponse(String token, Long userId, String username) {}

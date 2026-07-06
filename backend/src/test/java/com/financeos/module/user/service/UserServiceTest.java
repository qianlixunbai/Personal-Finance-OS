package com.financeos.module.user.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.financeos.common.BusinessException;
import com.financeos.module.auth.util.JwtUtil;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.entity.User;
import com.financeos.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Test
    void loginRejectsInactiveUser() {
        UserMapper userMapper = mock(UserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserService service = new UserService(userMapper, passwordEncoder, jwtUtil);

        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPasswordHash("hash");
        user.setStatus("INACTIVE");

        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.login(new LoginRequest("alice", "secret")));

        verify(jwtUtil, never()).generateToken(any(), any());
    }
}

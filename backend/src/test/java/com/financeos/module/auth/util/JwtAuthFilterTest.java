package com.financeos.module.auth.util;

import com.financeos.module.user.entity.User;
import com.financeos.module.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inactiveUserTokenDoesNotAuthenticateRequest() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserMapper userMapper = mock(UserMapper.class);
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, userMapper);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(jwtUtil.parseToken("token")).thenReturn(claims);

        User user = new User();
        user.setId(1L);
        user.setStatus("INACTIVE");
        when(userMapper.selectById(1L)).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}

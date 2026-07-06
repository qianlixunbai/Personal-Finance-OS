package com.financeos.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.common.BusinessException;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.LoginResponse;
import com.financeos.module.user.dto.RegisterRequest;
import com.financeos.module.user.entity.User;
import com.financeos.module.user.mapper.UserMapper;
import com.financeos.module.auth.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public void register(RegisterRequest req) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.username())) > 0) {
            throw new BusinessException(400, "用户名已存在");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, req.email())) > 0) {
            throw new BusinessException(400, "邮箱已注册");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        userMapper.insert(user);
    }

    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.username()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已禁用");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername());
    }
}

package com.example.wjyaiagent.auth.security;

import com.example.wjyaiagent.user.dto.UserInfo;
import java.io.Serializable;
import java.time.Instant;

public record StudentPrincipal(UserInfo user, Instant expiresAt) implements Serializable { }

package com.example.wjyaiagent.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record User(long id, String username, @JsonIgnore String passwordHash, String role, String status) {
    public boolean isActiveAccount() {
        return ("STUDENT".equals(role) || "ADMIN".equals(role)) && "ACTIVE".equals(status);
    }
    public boolean isActiveStudent() {
        return "STUDENT".equals(role) && "ACTIVE".equals(status);
    }
}


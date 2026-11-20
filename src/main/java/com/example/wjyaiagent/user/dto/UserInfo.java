package com.example.wjyaiagent.user.dto;

import com.example.wjyaiagent.user.entity.User;
import java.io.Serializable;

public record UserInfo(long id, String username, String role) implements Serializable {
    public static UserInfo from(User user) {
        return new UserInfo(user.id(), user.username(), user.role());
    }
}

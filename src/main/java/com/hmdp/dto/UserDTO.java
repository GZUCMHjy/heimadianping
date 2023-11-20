package com.hmdp.dto;

import lombok.Data;
// DTO一般用户数据的脱敏
// 去除不必要的字段信息，用于数据的安全
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}

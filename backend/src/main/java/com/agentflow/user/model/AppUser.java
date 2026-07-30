package com.agentflow.user.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 中文：{@code app_user} 表的一行在 Java 中的映射。它只负责数据形状，不放注册、登录等业务流程。
 * English: Java mapping for one {@code app_user} row. It models data only; registration
 * and login workflows belong in the service layer.
 */
@TableName("app_user")
public class AppUser {

    /**
     * 中文：由 MyBatis-Plus 的 ASSIGN_ID 策略在插入前生成，和 V1 中的 BIGINT 主键对应。
     * English: Generated before insert by MyBatis-Plus ASSIGN_ID, matching V1's BIGINT key.
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String username;
    private String email;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("display_name")
    private String displayName;

    private String role;
    private String status;

    @TableField("last_login_at")
    private OffsetDateTime lastLoginAt;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    /**
     * 中文：V1 用时间戳表达软删除。当前注册切片不执行删除，因此暂不标记 @TableLogic；
     * 后续删除策略会在独立切片中决定。
     * English: V1 represents soft deletion with a timestamp. Registration does not
     * delete users, so @TableLogic is intentionally deferred to a dedicated later slice.
     */
    @TableField("deleted_at")
    private OffsetDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(OffsetDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}

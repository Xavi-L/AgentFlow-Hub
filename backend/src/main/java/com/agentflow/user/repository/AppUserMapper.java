package com.agentflow.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agentflow.user.model.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 中文：用户表的数据访问边界。BaseMapper 已提供 insert、selectCount 等基础 SQL，
 * 因此本注册切片不需要 XML 或手写 CRUD。
 * English: Data-access boundary for users. BaseMapper already supplies insert and
 * selectCount, so this registration slice needs neither XML nor handwritten CRUD.
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}

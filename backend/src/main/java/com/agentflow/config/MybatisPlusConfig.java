package com.agentflow.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 中文：为 MyBatis-Plus 注册 PostgreSQL 分页拦截器。没有它时，Mapper 的 selectPage 调用
 * 不会可靠地改写为带 LIMIT/OFFSET 和 count 查询的 PostgreSQL SQL。
 *
 * <p>English: Registers MyBatis-Plus pagination for PostgreSQL. Without it, a mapper's
 * selectPage call is not reliably rewritten into PostgreSQL LIMIT/OFFSET and count SQL.
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 中文：分页拦截器应在后续可能新增的其他 InnerInterceptor 之后添加。
     * English: Pagination should be added after any other InnerInterceptor introduced later.
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}

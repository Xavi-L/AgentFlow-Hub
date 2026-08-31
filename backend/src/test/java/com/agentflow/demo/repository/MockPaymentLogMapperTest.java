package com.agentflow.demo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.demo.model.MockPaymentLog;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

class MockPaymentLogMapperTest {

    @Test
    void shouldFilterByOrderNumberWithStableParameterizedLimitAndNoOwnerPredicate() {
        MappedStatement statement = paymentLogStatement();
        BoundSql sql = statement.getBoundSql(parameters("order_1024", null, 10));
        String normalizedSql = normalize(sql);

        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        assertThat(normalizedSql).contains(
                "FROM mock_payment_log",
                "WHERE order_no = ?",
                "ORDER BY occurred_at DESC, id DESC",
                "LIMIT ?"
        );
        assertThat(normalizedSql).doesNotContain(
                "error_code = ?",
                "user_id",
                "user_no",
                "owner",
                "JOIN",
                "LIMIT 10",
                "INSERT",
                "UPDATE",
                "DELETE"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("orderNo", "limit");
    }

    @Test
    void shouldFilterByErrorCodeWithStableParameterizedLimitAndNoOwnerPredicate() {
        BoundSql sql = paymentLogStatement().getBoundSql(parameters(null, "E_PAY_TIMEOUT", 20));
        String normalizedSql = normalize(sql);

        assertThat(normalizedSql).contains(
                "FROM mock_payment_log",
                "WHERE error_code = ?",
                "ORDER BY occurred_at DESC, id DESC",
                "LIMIT ?"
        );
        assertThat(normalizedSql).doesNotContain(
                "order_no = ?",
                "user_id",
                "user_no",
                "owner",
                "JOIN",
                "LIMIT 20"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("errorCode", "limit");
    }

    @Test
    void shouldCombineBothFiltersWithAndAndKeepTheStableOrder() {
        MappedStatement statement = paymentLogStatement();
        BoundSql sql = statement.getBoundSql(parameters("order_1024", "E_PAY_TIMEOUT", 7));
        String normalizedSql = normalize(sql);

        assertThat(normalizedSql).contains(
                "WHERE order_no = ?",
                "AND error_code = ?",
                "ORDER BY occurred_at DESC, id DESC",
                "LIMIT ?"
        );
        assertThat(normalizedSql).doesNotContain(" OR ", "user_id", "owner", "LIMIT 7");
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("orderNo", "errorCode", "limit");
        assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(MockPaymentLog.class);
    }

    private static MappedStatement paymentLogStatement() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(MockPaymentLogMapper.class);
        String statementId = MockPaymentLogMapper.class.getName() + ".selectByFilters";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        return configuration.getMappedStatement(statementId);
    }

    private static Map<String, Object> parameters(String orderNo, String errorCode, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("orderNo", orderNo);
        parameters.put("errorCode", errorCode);
        parameters.put("limit", limit);
        return parameters;
    }

    private static String normalize(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").trim();
    }
}

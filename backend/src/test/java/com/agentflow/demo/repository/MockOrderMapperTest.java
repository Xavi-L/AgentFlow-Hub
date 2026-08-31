package com.agentflow.demo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.demo.model.MockOrder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

class MockOrderMapperTest {

    @Test
    void shouldRegisterTheGlobalReadOnlyOrderLookupWithoutAnOwnerPredicate() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(MockOrderMapper.class);

        String statementId = MockOrderMapper.class.getName() + ".selectByOrderNo";
        assertThat(configuration.hasStatement(statementId, false)).isTrue();
        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql sql = statement.getBoundSql(Map.of("orderNo", "order_1024"));

        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        assertThat(sql.getSql()).contains(
                "SELECT id",
                "order_no",
                "user_no",
                "amount",
                "payment_status",
                "error_code",
                "FROM mock_order",
                "WHERE order_no = ?"
        );
        assertThat(sql.getSql()).doesNotContain(
                "user_id",
                "owner",
                "JOIN",
                "INSERT",
                "UPDATE",
                "DELETE"
        );
        assertThat(sql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("orderNo");
        assertThat(statement.getResultMaps().getFirst().getType()).isEqualTo(MockOrder.class);
        assertThat(statement.getResultMaps().getFirst().getResultMappings())
                .extracting(ResultMapping::getProperty)
                .containsExactly(
                        "id",
                        "orderNo",
                        "userNo",
                        "paymentStatus",
                        "errorCode",
                        "createdAt",
                        "updatedAt"
                );
    }
}

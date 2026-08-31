package com.agentflow.demo.repository;

import com.agentflow.demo.model.MockPaymentLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Read-only V26 data access for globally shared demo payment logs. */
@Mapper
public interface MockPaymentLogMapper extends BaseMapper<MockPaymentLog> {

    @Results(id = "mockPaymentLogResult", value = {
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "orderNo", column = "order_no"),
            @Result(property = "traceId", column = "trace_id"),
            @Result(property = "logLevel", column = "log_level"),
            @Result(property = "errorCode", column = "error_code"),
            @Result(property = "occurredAt", column = "occurred_at"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            <script>
            SELECT id,
                   order_no,
                   trace_id,
                   log_level,
                   error_code,
                   message,
                   occurred_at,
                   created_at
            FROM mock_payment_log
            <where>
                <if test="orderNo != null">
                    order_no = #{orderNo}
                </if>
                <if test="errorCode != null">
                    AND error_code = #{errorCode}
                </if>
            </where>
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<MockPaymentLog> selectByFilters(
            @Param("orderNo") String orderNo,
            @Param("errorCode") String errorCode,
            @Param("limit") int limit
    );
}

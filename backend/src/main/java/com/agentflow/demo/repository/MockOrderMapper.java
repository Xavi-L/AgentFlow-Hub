package com.agentflow.demo.repository;

import com.agentflow.demo.model.MockOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** Read-only V26 data access for the globally shared demo-order fixture. */
@Mapper
public interface MockOrderMapper extends BaseMapper<MockOrder> {

    @Results(id = "mockOrderResult", value = {
            @Result(id = true, property = "id", column = "id"),
            @Result(property = "orderNo", column = "order_no"),
            @Result(property = "userNo", column = "user_no"),
            @Result(property = "paymentStatus", column = "payment_status"),
            @Result(property = "errorCode", column = "error_code"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("""
            SELECT id,
                   order_no,
                   user_no,
                   amount,
                   currency,
                   status,
                   payment_status,
                   error_code,
                   created_at,
                   updated_at
            FROM mock_order
            WHERE order_no = #{orderNo}
            """)
    MockOrder selectByOrderNo(@Param("orderNo") String orderNo);
}

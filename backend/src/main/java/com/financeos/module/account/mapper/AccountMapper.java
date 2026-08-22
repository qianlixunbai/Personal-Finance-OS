package com.financeos.module.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.account.entity.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    @Select("SELECT * FROM accounts WHERE user_id = #{userId} AND id = #{accountId}")
    Account findByUserIdAndId(@Param("userId") Long userId, @Param("accountId") Long accountId);

    @Select("""
            <script>
            SELECT * FROM accounts WHERE user_id = #{userId} AND id IN
            <foreach collection="accountIds" item="accountId" open="(" separator="," close=")">#{accountId}</foreach>
            </script>
            """)
    List<Account> findOwnedByIds(@Param("userId") Long userId, @Param("accountIds") List<Long> accountIds);

    @Select("SELECT set_config('lock_timeout', #{timeout}, true)")
    String configureLocalLockTimeout(@Param("timeout") String timeout);

    @Select("""
            <script>
            SELECT id, user_id, name, type, currency, balance, status, created_at, updated_at
            FROM accounts
            WHERE user_id = #{userId}
              AND id IN
              <foreach collection="accountIds" item="accountId" open="(" separator="," close=")">
                #{accountId}
              </foreach>
            ORDER BY id ASC
            FOR UPDATE
            </script>
            """)
    List<Account> selectOwnedForUpdate(@Param("userId") Long userId,
                                       @Param("accountIds") List<Long> accountIds);

    @Update("""
            UPDATE accounts SET balance = #{newBalance}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND user_id = #{userId}
            """)
    int updateBalance(@Param("userId") Long userId, @Param("accountId") Long accountId,
                      @Param("newBalance") BigDecimal newBalance);

    @Update("""
            UPDATE accounts
            SET name = #{name}, type = #{type}, currency = #{currency}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND user_id = #{userId}
            """)
    int updateMetadata(@Param("userId") Long userId, @Param("accountId") Long accountId,
                       @Param("name") String name, @Param("type") String type,
                       @Param("currency") String currency);

    @Update("""
            UPDATE accounts SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{accountId} AND user_id = #{userId}
            """)
    int deactivate(@Param("userId") Long userId, @Param("accountId") Long accountId);
}

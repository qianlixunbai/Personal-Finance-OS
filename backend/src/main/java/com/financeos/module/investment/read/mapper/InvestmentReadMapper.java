package com.financeos.module.investment.read.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.financeos.module.asset.entity.Asset;

import java.util.List;

@Mapper
public interface InvestmentReadMapper {
    @Select("""
            SELECT count(*) AS position_count,
                   count(*) FILTER (WHERE position_status = 'OPEN') AS open_position_count,
                   count(*) FILTER (WHERE position_status = 'CLOSED') AS closed_position_count,
                   COALESCE(sum(total_cost) FILTER (WHERE position_status = 'OPEN'), 0.00) AS open_total_cost,
                   COALESCE(sum(realized_profit_loss), 0.00) AS cumulative_realized_profit_loss
            FROM assets
            WHERE user_id = #{userId} AND position_mode = 'TRANSACTION_DRIVEN'
            """)
    InvestmentPortfolioStatisticsRow selectPortfolioStatistics(@Param("userId") Long userId);

    @Select("""
            SELECT a.*, i.symbol AS symbol, i.market AS market
            FROM assets a
            JOIN investment_instruments i ON i.id = a.instrument_id AND i.user_id = a.user_id
            WHERE a.user_id = #{userId}
              AND a.position_mode = 'TRANSACTION_DRIVEN'
              AND a.position_status = 'OPEN'
            ORDER BY a.instrument_id ASC, a.account_id ASC, a.id ASC
            """)
    List<Asset> selectOpenTransactionDrivenPositionAssets(@Param("userId") Long userId);

    @Select("""
            SELECT a.id AS position_id, a.account_id, ac.name AS account_name, a.instrument_id,
                   i.symbol AS instrument_symbol, i.name AS instrument_name, i.market AS instrument_market,
                   i.asset_class AS instrument_asset_class, i.quote_currency AS instrument_quote_currency,
                   a.position_mode, a.position_status, a.quantity, a.avg_cost AS average_cost,
                   a.total_cost, a.realized_profit_loss AS cumulative_realized_profit_loss
            FROM assets a
            JOIN accounts ac ON ac.id = a.account_id AND ac.user_id = a.user_id
            JOIN investment_instruments i ON i.id = a.instrument_id AND i.user_id = a.user_id
            WHERE a.user_id = #{userId} AND a.id = #{positionId} AND a.position_mode = 'TRANSACTION_DRIVEN'
            """)
    InvestmentReadRow selectPositionDetail(@Param("userId") Long userId, @Param("positionId") Long positionId);

    @Select("""
            SELECT a.*, i.symbol AS symbol, i.market AS market
            FROM assets a
            JOIN investment_instruments i ON i.id = a.instrument_id AND i.user_id = a.user_id
            WHERE a.user_id = #{userId} AND a.id = #{positionId} AND a.position_mode = 'TRANSACTION_DRIVEN'
            """)
    Asset selectOwnedTransactionDrivenPositionAsset(@Param("userId") Long userId, @Param("positionId") Long positionId);
    @Select("""
            <script>
            SELECT a.id AS position_id, a.account_id, ac.name AS account_name, a.instrument_id,
                   i.symbol AS instrument_symbol, i.name AS instrument_name, i.market AS instrument_market,
                   i.asset_class AS instrument_asset_class, i.quote_currency AS instrument_quote_currency,
                   a.position_mode, a.position_status, a.quantity, a.avg_cost AS average_cost,
                   a.total_cost, a.realized_profit_loss AS cumulative_realized_profit_loss
            FROM assets a
            JOIN accounts ac ON ac.id = a.account_id AND ac.user_id = a.user_id
            JOIN investment_instruments i ON i.id = a.instrument_id AND i.user_id = a.user_id
            WHERE a.user_id = #{criteria.userId}
              AND a.position_mode = 'TRANSACTION_DRIVEN'
              <if test="criteria.status != 'ALL'">AND a.position_status = #{criteria.status}</if>
              <if test="criteria.accountId != null">AND a.account_id = #{criteria.accountId}</if>
              <if test="criteria.instrumentId != null">AND a.instrument_id = #{criteria.instrumentId}</if>
              <if test="criteria.lastPositionId != null">
                AND (a.instrument_id &gt; #{criteria.lastInstrumentId}
                  OR (a.instrument_id = #{criteria.lastInstrumentId} AND a.account_id &gt; #{criteria.lastAccountId})
                  OR (a.instrument_id = #{criteria.lastInstrumentId} AND a.account_id = #{criteria.lastAccountId}
                      AND a.id &gt; #{criteria.lastPositionId}))
              </if>
            ORDER BY a.instrument_id ASC, a.account_id ASC, a.id ASC
            LIMIT #{criteria.limit}
            </script>
            """)
    List<InvestmentReadRow> selectPositions(@Param("criteria") PositionReadCriteria criteria);

    @Select("SELECT EXISTS (SELECT 1 FROM accounts WHERE user_id = #{userId} AND id = #{id})")
    boolean ownedAccount(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT EXISTS (SELECT 1 FROM investment_instruments WHERE user_id = #{userId} AND id = #{id})")
    boolean ownedInstrument(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT EXISTS (SELECT 1 FROM assets WHERE user_id = #{userId} AND id = #{id} AND position_mode = 'TRANSACTION_DRIVEN')")
    boolean ownedPosition(@Param("userId") Long userId, @Param("id") Long id);

    @Select("""
            <script>
            WITH logical_rows AS (
                SELECT original.id AS logical_transaction_id, original.asset_id AS position_id,
                       original.account_id, account.name AS account_name, asset.instrument_id,
                       instrument.symbol AS instrument_symbol, instrument.name AS instrument_name,
                       instrument.market AS instrument_market, instrument.asset_class AS instrument_asset_class,
                       instrument.quote_currency AS instrument_quote_currency,
                       COALESCE(replacement.transaction_type, original.transaction_type) AS transaction_type,
                       original.trade_time AS effective_trade_time,
                       COALESCE(replacement.quantity, original.quantity) AS quantity,
                       COALESCE(replacement.unit_price, original.unit_price) AS unit_price,
                       COALESCE(replacement.gross_amount, original.gross_amount) AS gross_amount,
                       COALESCE(replacement.fee_amount, original.fee_amount) AS fee_amount,
                       COALESCE(replacement.tax_amount, original.tax_amount) AS tax_amount,
                       COALESCE(replacement.net_amount, original.net_amount) AS net_amount,
                       COALESCE(replacement.released_cost_amount, original.released_cost_amount) AS released_cost_amount,
                       COALESCE(replacement.realized_profit_loss, original.realized_profit_loss) AS realized_profit_loss,
                       CASE WHEN correction.id IS NOT NULL THEN 'REPLACED'
                            WHEN reversal.id IS NOT NULL THEN 'REVERSED' ELSE 'UNCHANGED' END AS correction_status,
                       CASE WHEN reversal.id IS NOT NULL AND correction.id IS NULL THEN false ELSE true END AS effective,
                       CASE WHEN correction.id IS NOT NULL THEN correction.created_at ELSE reversal.created_at END AS correction_created_at,
                       COALESCE(replacement.note, original.note) AS note,
                       COALESCE(replacement.external_reference, original.external_reference) AS external_reference
                FROM investment_transactions original
                JOIN assets asset ON asset.id = original.asset_id AND asset.user_id = original.user_id
                JOIN accounts account ON account.id = original.account_id AND account.user_id = original.user_id
                JOIN investment_instruments instrument ON instrument.id = asset.instrument_id AND instrument.user_id = original.user_id
                LEFT JOIN investment_transaction_corrections correction
                    ON correction.user_id = original.user_id AND correction.original_transaction_id = original.id
                LEFT JOIN investment_transactions replacement
                    ON replacement.user_id = correction.user_id AND replacement.id = correction.replacement_transaction_id
                LEFT JOIN investment_transactions reversal
                    ON reversal.user_id = original.user_id AND reversal.original_transaction_id = original.id
                   AND reversal.transaction_type = 'REVERSAL'
                WHERE original.user_id = #{criteria.userId}
                  AND original.transaction_type IN ('OPENING_POSITION', 'BUY', 'SELL', 'DIVIDEND')
                  AND original.original_transaction_id IS NULL
                  AND original.correction_group_id IS NULL
            )
            SELECT * FROM logical_rows
            WHERE 1 = 1
              <if test="criteria.positionId != null">AND position_id = #{criteria.positionId}</if>
              <if test="criteria.accountId != null">AND account_id = #{criteria.accountId}</if>
              <if test="criteria.instrumentId != null">AND instrument_id = #{criteria.instrumentId}</if>
              <if test="criteria.type != null">AND transaction_type = #{criteria.type}</if>
              <if test="criteria.correctionStatus != null">AND correction_status = #{criteria.correctionStatus}</if>
              <if test="criteria.from != null">AND effective_trade_time &gt;= #{criteria.from}</if>
              <if test="criteria.to != null">AND effective_trade_time &lt; #{criteria.to}</if>
              <if test="criteria.lastLogicalTransactionId != null">
                AND (effective_trade_time &lt; #{criteria.lastEffectiveTradeTime}
                  OR (effective_trade_time = #{criteria.lastEffectiveTradeTime}
                      AND logical_transaction_id &lt; #{criteria.lastLogicalTransactionId}))
              </if>
            ORDER BY effective_trade_time DESC, logical_transaction_id DESC
            LIMIT #{criteria.limit}
            </script>
            """)
    List<InvestmentReadRow> selectLogicalTransactions(@Param("criteria") LogicalTransactionReadCriteria criteria);
}

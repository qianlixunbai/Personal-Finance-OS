package com.financeos.module.investment.instrument.service;

import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.mapper.InvestmentInstrumentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InvestmentInstrumentQueryService {
    private final InvestmentInstrumentMapper instrumentMapper;

    public InvestmentInstrumentQueryService(InvestmentInstrumentMapper instrumentMapper) {
        this.instrumentMapper = instrumentMapper;
    }

    public InvestmentInstrument findByUserIdAndId(Long userId, Long instrumentId) {
        return instrumentMapper.findByUserIdAndId(userId, instrumentId);
    }

    public InvestmentInstrument findByUserIdAndMarketAndSymbol(Long userId, String market, String symbol) {
        return instrumentMapper.findByUserIdAndMarketAndSymbol(userId, market, symbol);
    }

    public List<InvestmentInstrument> listByUserId(Long userId) {
        return instrumentMapper.listByUserId(userId);
    }

    public boolean existsByUserIdAndId(Long userId, Long instrumentId) {
        return instrumentMapper.existsByUserIdAndId(userId, instrumentId);
    }
}

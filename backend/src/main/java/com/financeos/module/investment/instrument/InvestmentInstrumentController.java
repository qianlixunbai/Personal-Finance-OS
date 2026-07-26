package com.financeos.module.investment.instrument;

import com.financeos.common.ApiResponse;
import com.financeos.module.investment.instrument.dto.InvestmentInstrumentRequest;
import com.financeos.module.investment.instrument.dto.InvestmentInstrumentResponse;
import com.financeos.module.investment.instrument.service.CreateInvestmentInstrumentCommand;
import com.financeos.module.investment.instrument.service.InvestmentInstrumentCommandService;
import com.financeos.module.investment.instrument.service.InvestmentInstrumentQueryService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/investment/instruments")
public class InvestmentInstrumentController {
    private final InvestmentInstrumentCommandService commandService;
    private final InvestmentInstrumentQueryService queryService;

    public InvestmentInstrumentController(InvestmentInstrumentCommandService commandService,
                                          InvestmentInstrumentQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<List<InvestmentInstrumentResponse>> list(Authentication auth) {
        return ApiResponse.ok(queryService.listByUserId((Long) auth.getPrincipal()).stream()
                .map(InvestmentInstrumentResponse::from).toList());
    }

    @PostMapping
    public ApiResponse<InvestmentInstrumentResponse> create(@Valid @RequestBody InvestmentInstrumentRequest request,
                                                             Authentication auth) {
        return ApiResponse.ok(InvestmentInstrumentResponse.from(commandService.create((Long) auth.getPrincipal(),
                new CreateInvestmentInstrumentCommand(request.symbol(), request.name(), request.market(),
                        request.assetClass(), request.quoteCurrency()))));
    }
}

package org.rrpe.intelligence.controller;

import org.rrpe.intelligence.analysis.api.CancellationAnalysisRequest;
import org.rrpe.intelligence.analysis.service.CancellationAnalysisService;
import org.rrpe.intelligence.analysis.service.CancellationAnalysisService.AnalysisResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses/cancellations")
public class CancellationAnalysisController {
    private final CancellationAnalysisService service;

    public CancellationAnalysisController(CancellationAnalysisService service) {
        this.service = service;
    }

    @PostMapping
    public AnalysisResult analyse(@RequestBody CancellationAnalysisRequest request) {
        return service.analyse(request);
    }
}

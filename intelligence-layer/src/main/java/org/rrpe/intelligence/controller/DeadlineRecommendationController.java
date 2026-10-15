package org.rrpe.intelligence.controller;

import org.rrpe.intelligence.analysis.model.ChainDeadlineRecommendation;
import org.rrpe.intelligence.analysis.model.ChainDeadlineRecommendation.Request;
import org.rrpe.intelligence.analysis.service.DeadlineRecommendationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations/deadlines")
public class DeadlineRecommendationController {
    private final DeadlineRecommendationService service;

    public DeadlineRecommendationController(DeadlineRecommendationService service) {
        this.service = service;
    }

    @PostMapping
    public ChainDeadlineRecommendation recommend(@RequestBody Request request) {
        return service.recommend(request);
    }
}

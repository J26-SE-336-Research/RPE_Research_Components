package org.rrpe.intelligence.controller;

import org.rrpe.intelligence.analysis.api.ClassificationRequest;
import org.rrpe.intelligence.analysis.model.HealthAssessment;
import org.rrpe.intelligence.analysis.service.ServiceHealthClassifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/classifications")
public class ClassificationController {

    private final ServiceHealthClassifier classifier;

    public ClassificationController(ServiceHealthClassifier classifier) {
        this.classifier = classifier;
    }

    @PostMapping
    public HealthAssessment classify(
            @RequestBody ClassificationRequest request
    ) {
        return classifier.classify(
                request.recent(),
                request.baseline()
        );
    }
}

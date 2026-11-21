package com.rpgjr.mirana.controller.github;

import com.rpgjr.mirana.config.GithubProperties;
import com.rpgjr.mirana.service.github.GithubWebhookService;
import com.rpgjr.mirana.utils.WebhookSignature;

import com.rpgjr.mirana.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
public class GithubWebhookController {

    private final GithubProperties props;
    private final GithubWebhookService service;

    public GithubWebhookController(GithubProperties props, GithubWebhookService service) {
        this.props = props;
        this.service = service;
    }

    /** The signature covers the exact bytes GitHub sent, so the body is taken raw. */
    @PostMapping("/webhook")
    public void receive(@RequestBody byte[] body,
                        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                        @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                        @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery) {
        if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_OFF", "GitHub webhook is not configured.");
        }
        if (!WebhookSignature.valid(props.webhookSecret(), body, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "Signature does not match.");
        }
        if (event == null || delivery == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_WEBHOOK", "Missing GitHub headers.");
        }
        service.handle(delivery, event, body);
    }
}

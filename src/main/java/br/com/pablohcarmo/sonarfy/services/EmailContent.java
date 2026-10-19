package br.com.pablohcarmo.sonarfy.services;

import java.util.Map;

public record EmailContent(
        String htmlBody,
        String textBody,
        Map<String, String> inlineResources
) {
    public EmailContent {
        inlineResources = Map.copyOf(inlineResources);
    }
}

package br.com.pablohcarmo.sonarfy.dto;

import java.util.UUID;

public record EmailChangeMailDto(
        UUID id,
        UUID requestId,
        long userId,
        String kind,
        String recipient,
        int attempts,
        UUID leaseId
) {}

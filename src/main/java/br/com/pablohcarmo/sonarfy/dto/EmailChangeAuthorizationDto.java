package br.com.pablohcarmo.sonarfy.dto;

import java.util.UUID;

public record EmailChangeAuthorizationDto(long userId, UUID requestId) {}

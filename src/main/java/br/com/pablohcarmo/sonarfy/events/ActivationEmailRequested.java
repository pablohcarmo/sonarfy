package br.com.pablohcarmo.sonarfy.events;

// Transporta apenas a identificação, não uma entidade com estado antigo.
// Útil para eventos de domínio, apenas para notificar o que algo aconteceu, sem carregar toda a entidade.
public record ActivationEmailRequested(Long userId) {
}

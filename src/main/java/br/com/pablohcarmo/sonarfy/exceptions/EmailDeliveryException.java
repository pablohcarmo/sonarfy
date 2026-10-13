package br.com.pablohcarmo.sonarfy.exceptions;

public class EmailDeliveryException extends RuntimeException {
    // Permitir reconhecer uma falha de entrega sem capturar qualquer erro específico, apenas para fins de logging e rastreamento.
    public EmailDeliveryException(String message, Throwable cause) {
        // Mantém a mensagem da aplicação ea falha original como causa.
        super(message, cause);
    }
}

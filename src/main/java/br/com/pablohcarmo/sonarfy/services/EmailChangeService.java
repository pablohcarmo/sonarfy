package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.EmailChangeRequestDto;
import br.com.pablohcarmo.sonarfy.repositories.EmailChangeRequestRepository;
import br.com.pablohcarmo.sonarfy.repositories.EmailChangeMailRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailChangeService {
    private final EmailChangeRequestRepository requests;
    private final EmailChangeMailRepository mails;

    public EmailChangeService(EmailChangeRequestRepository requests, EmailChangeMailRepository mails) {
        this.requests = requests;
        this.mails = mails;
    }

    // Coordena a substituição e o pedido de envio em uma única transação.
    @Transactional
    public void replaceRequest(EmailChangeRequestDto request) {
        requests.replaceRequest(request);
        mails.enqueue(request.id(), request.userId(), "CONFIRM_NEW_EMAIL", request.newEmail(), request.issuedAt());
    }
}

package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.ProfileDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.AvatarUploadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "app.avatar.enabled", havingValue = "true")
public class AvatarController {
    private final AvatarUploadService avatars;
    public AvatarController(AvatarUploadService avatars) { this.avatars = avatars; }

    // Nome do arquivo e IDs enviados não determinam a chave do blob nem a conta.
    @PostMapping(value = "/api/profile/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileDto> upload(@AuthenticationPrincipal User actor, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Integer cropX, @RequestParam(required = false) Integer cropY,
            @RequestParam(required = false) Integer cropSize, MultipartHttpServletRequest request) {
        // Rejeita o lote inteiro, inclusive arquivos extras com outro nome de campo.
        if (request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send exactly one file.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.upload(actor.getId(), file, cropX, cropY, cropSize));
    }
}

package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.config.WallpaperProperties;
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
@ConditionalOnProperty(name = "app.wallpaper.enabled", havingValue = "true")
public class WallpaperController {
    private final AvatarUploadService images;
    private final WallpaperProperties limits;
    public WallpaperController(AvatarUploadService images, WallpaperProperties limits) { this.images = images; this.limits = limits; }

    // Preserva a imagem inteira; o enquadramento responsivo pertence ao frontend.
    @PostMapping(value = "/api/profile/me/wallpaper", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileDto> upload(@AuthenticationPrincipal User actor, @RequestPart("file") MultipartFile file,
                                            MultipartHttpServletRequest request) {
        // Rejeita o lote inteiro, inclusive arquivos extras com outro nome de campo.
        if (request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send exactly one file.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(images.wallpaper(actor.getId(), file, limits.maxWidth(), limits.maxHeight()));
    }
}

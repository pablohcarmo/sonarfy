package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.ProfileDto;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

public class AvatarUploadService {
    private static final Logger LOG = LoggerFactory.getLogger(AvatarUploadService.class);
    private final AvatarImageProcessor images;
    private final AzureAvatarStorage storage;
    private final UserRepository users;
    private final EntityManager entities;
    private final ProfileService profiles;
    private final TransactionTemplate transactions;

    public AvatarUploadService(AvatarImageProcessor images, AzureAvatarStorage storage, UserRepository users,
                               EntityManager entities, ProfileService profiles, PlatformTransactionManager manager) {
        this.images = images; this.storage = storage; this.users = users; this.entities = entities;
        this.profiles = profiles; this.transactions = new TransactionTemplate(manager);
    }

    // Envia antes da transação; falha no armazenamento preserva a referência atual.
    public ProfileDto upload(Long actorId, MultipartFile file, Integer cropX, Integer cropY, Integer cropSize) {
        return replace(actorId, images.png(file, cropX, cropY, cropSize), false);
    }

    public ProfileDto wallpaper(Long actorId, MultipartFile file, int maxWidth, int maxHeight) {
        return replace(actorId, images.wallpaper(file, maxWidth, maxHeight), true);
    }

    // Compartilha transação e compensação, mantendo separados os prefixos e campos de cada mídia.
    private ProfileDto replace(Long actorId, byte[] png, boolean wallpaper) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Profile image upload requires its own transaction boundary.");
        String folder = wallpaper ? "wallpapers" : "avatars";
        String key = folder + "/" + actorId + "/" + UUID.randomUUID() + ".png";
        String url = storage.url(key);
        if (url.length() > 255) throw new IllegalStateException("Profile image URL exceeds the schema limit.");
        try { storage.upload(key, png); }
        catch (RuntimeException exception) {
            if (!(exception instanceof com.azure.storage.blob.models.BlobStorageException conflict
                    && (conflict.getStatusCode() == 409 || conflict.getStatusCode() == 412))) cleanup(key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage unavailable.");
        }
        boolean[] registered = {false};
        try {
            return transactions.execute(status -> {
                String[] previous = {null};
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { cleanup(previous[0]); }
                    @Override public void afterCompletion(int result) {
                        if (result == STATUS_ROLLED_BACK) cleanup(key);
                        if (result == STATUS_UNKNOWN) LOG.warn("Profile image transaction outcome unknown for object {}", key);
                    }
                });
                registered[0] = true;
                var user = users.findById(actorId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
                if (!user.isEnabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                previous[0] = storage.ownedKey(actorId, wallpaper ? user.getWallpaper() : user.getAvatar(), folder);
                if (wallpaper) user.setWallpaper(url); else user.setAvatar(url);
                users.saveAndFlush(user);
                return profiles.current(actorId);
            });
        } catch (RuntimeException exception) {
            if (!registered[0]) cleanup(key);
            throw exception;
        }
    }

    // Falha na limpeza não desfaz nem apresenta como falha uma atualização já confirmada.
    private void cleanup(String key) {
        if (key == null) return;
        try { storage.delete(key); }
        catch (RuntimeException exception) { LOG.warn("Profile image cleanup pending for object {}", key); }
    }
}

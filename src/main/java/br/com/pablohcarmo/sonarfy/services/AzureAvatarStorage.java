package br.com.pablohcarmo.sonarfy.services;

import com.azure.core.util.*;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import java.time.Duration;

public class AzureAvatarStorage {
    private final BlobContainerClient container;
    private final Duration timeout;

    public AzureAvatarStorage(BlobContainerClient container, int timeoutSeconds) {
        this.container = container;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    // Não sobrescreve chaves versionadas; publica somente o PNG gerado.
    public void upload(String key, byte[] png) {
        container.getBlobClient(key).uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(png))
                .setHeaders(new BlobHttpHeaders().setContentType("image/png").setCacheControl("public, max-age=31536000, immutable"))
                .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")), timeout, Context.NONE);
    }

    public String url(String key) { return container.getBlobClient(key).getBlobUrl(); }

    public void delete(String key) {
        container.getBlobClient(key).deleteIfExistsWithResponse(DeleteSnapshotsOptionType.INCLUDE, null, timeout, Context.NONE);
    }

    public String ownedKey(Long userId, String url) {
        String prefix = url("avatars/" + userId + "/");
        if (url == null || !url.startsWith(prefix)) return null;
        String suffix = url.substring(prefix.length());
        return suffix.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.png")
                ? "avatars/" + userId + "/" + suffix : null;
    }
}

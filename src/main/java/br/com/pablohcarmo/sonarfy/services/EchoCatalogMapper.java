package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

// Normaliza e filtra itens da API do Spotify para o modelo relacional de Ecos.
final class EchoCatalogMapper {

    private static final Pattern SPOTIFY_ID = Pattern.compile("^[0-9A-Za-z]{1,64}$");
    private static final Pattern YEAR = Pattern.compile("^[0-9]{4}$");
    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");
    private static final Pattern DAY = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$");
    private static final int MAX_NAME_LENGTH = 500;
    private static final int MAX_COVER_LENGTH = 255;

    record NormalizedArtist(String spotifyId, String name) {}

    record NormalizedAlbum(String spotifyId, String primaryArtistSpotifyId, String primaryArtistName,
                           String name, String coverUrl, String releaseOn, String releasePrecision) {}

    record NormalizedListen(String trackSpotifyId, String trackName, Integer durationMs, boolean explicit,
                            NormalizedAlbum album, List<NormalizedArtist> artists, OffsetDateTime playedAt) {}

    record ReleaseDate(String releaseOn, String precision) {}

    private EchoCatalogMapper() {}

    static Optional<NormalizedListen> normalize(PlayHistoryItem item) {
        if (item == null || item.playedAt() == null || item.track() == null) return Optional.empty();
        TrackObject track = item.track();
        if (Boolean.TRUE.equals(track.isLocal())) return Optional.empty();
        if (track.type() != null && !"track".equalsIgnoreCase(track.type())) return Optional.empty();
        if (!isValidId(track.id()) || !isValidName(track.name())) return Optional.empty();
        if (track.durationMs() != null && track.durationMs() < 0) return Optional.empty();

        AlbumObject album = track.album();
        if (album == null || !isValidId(album.id()) || !isValidName(album.name())) return Optional.empty();

        List<NormalizedArtist> trackArtists = normalizeArtists(track.artists());
        List<NormalizedArtist> albumArtists = normalizeArtists(album.artists());
        if (trackArtists.isEmpty() && albumArtists.isEmpty()) return Optional.empty();

        List<NormalizedArtist> effectiveTrackArtists = trackArtists.isEmpty() ? albumArtists : trackArtists;
        NormalizedArtist primaryAlbumArtist = !albumArtists.isEmpty()
                ? albumArtists.getFirst()
                : effectiveTrackArtists.getFirst();

        ReleaseDate release = mapReleaseDate(album.releaseDate(), album.releaseDatePrecision());
        NormalizedAlbum normalizedAlbum = new NormalizedAlbum(
                album.id(), primaryAlbumArtist.spotifyId(), primaryAlbumArtist.name(),
                album.name().trim(), selectCoverUrl(album.images()), release.releaseOn(), release.precision());

        return Optional.of(new NormalizedListen(
                track.id(), track.name().trim(), track.durationMs(),
                Boolean.TRUE.equals(track.explicit()), normalizedAlbum,
                effectiveTrackArtists, item.playedAt()));
    }

    private static List<NormalizedArtist> normalizeArtists(List<ArtistObject> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Map<String, NormalizedArtist> unique = new LinkedHashMap<>();
        for (ArtistObject artist : raw) {
            if (artist != null && isValidId(artist.id()) && isValidName(artist.name())) {
                unique.putIfAbsent(artist.id(), new NormalizedArtist(artist.id(), artist.name().trim()));
            }
        }
        return List.copyOf(unique.values());
    }

    static ReleaseDate mapReleaseDate(String rawDate, String rawPrecision) {
        if (rawDate == null || rawPrecision == null || rawDate.startsWith("0000")) {
            return new ReleaseDate(null, null);
        }
        String date = rawDate.trim();
        return switch (rawPrecision.trim().toLowerCase(Locale.ROOT)) {
            case "year" -> YEAR.matcher(date).matches() ? new ReleaseDate(date, "ano") : new ReleaseDate(null, null);
            case "month" -> MONTH.matcher(date).matches() ? new ReleaseDate(date, "mes") : new ReleaseDate(null, null);
            case "day" -> DAY.matcher(date).matches() ? new ReleaseDate(date, "dia") : new ReleaseDate(null, null);
            default -> new ReleaseDate(null, null);
        };
    }

    static String selectCoverUrl(List<ImageObject> images) {
        if (images == null || images.isEmpty()) return null;
        return images.stream()
                .filter(img -> img != null && img.url() != null
                        && img.url().startsWith("https://") && img.url().length() <= MAX_COVER_LENGTH)
                .min(Comparator.comparingInt(img -> img.height() == null ? Integer.MAX_VALUE : Math.abs(img.height() - 300)))
                .map(ImageObject::url)
                .orElse(null);
    }

    private static boolean isValidId(String id) {
        return id != null && SPOTIFY_ID.matcher(id).matches();
    }

    private static boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.trim().length() <= MAX_NAME_LENGTH;
    }
}

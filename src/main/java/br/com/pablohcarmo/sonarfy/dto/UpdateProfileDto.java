package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.validation.ProfileTextDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public class UpdateProfileDto {
    @JsonDeserialize(using = ProfileTextDeserializer.class)
    private String displayName;
    @JsonDeserialize(using = ProfileTextDeserializer.class)
    private String biography;
    private boolean displayNamePresent;
    private boolean biographyPresent;

    public String getDisplayName() { return displayName; }
    public String getBiography() { return biography; }
    public boolean hasDisplayName() { return displayNamePresent; }
    public boolean hasBiography() { return biographyPresent; }

    // Setters distinguish omitted properties from explicit JSON null.
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.displayNamePresent = true;
    }

    public void setBiography(String biography) {
        this.biography = biography;
        this.biographyPresent = true;
    }
}

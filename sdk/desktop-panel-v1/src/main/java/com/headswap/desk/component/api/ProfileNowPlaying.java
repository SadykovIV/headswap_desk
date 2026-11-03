package com.headswap.desk.component.api;

import android.graphics.Bitmap;
import android.os.Bundle;

/** Snapshot of the current local player. Artwork is optional and at most 192 × 192 pixels (144 KiB). */
public final class ProfileNowPlaying {
    public static final int MAX_ART_EDGE = 192;
    public static final int MAX_LABEL_LENGTH = 128;
    private final String title;
    private final String artist;
    private final boolean playing;
    private final Bitmap artwork;

    private ProfileNowPlaying(String title, String artist, boolean playing, Bitmap artwork) {
        this.title = title;
        this.artist = artist;
        this.playing = playing;
        this.artwork = artwork;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public boolean isPlaying() { return playing; }
    public Bitmap getArtwork() { return artwork; }

    /** Encode only the SDK-approved fields when implementing the host or a test double. */
    public static Bundle encode(String title, String artist, boolean playing, Bitmap artwork) {
        ProfileNowPlaying track = fromFields(title, artist, playing, artwork);
        if (track == null) return null;
        Bundle fields = new Bundle();
        fields.putString("title", track.title);
        fields.putString("artist", track.artist);
        fields.putBoolean("playing", track.playing);
        if (track.artwork != null) fields.putParcelable("artwork", track.artwork);
        return fields;
    }

    @SuppressWarnings("deprecation")
    static ProfileNowPlaying fromBundle(Bundle fields) {
        if (fields == null) return null;
        fields.setClassLoader(null);
        return fromFields(fields.get("title"), fields.get("artist"), fields.get("playing"), fields.get("artwork"));
    }

    static ProfileNowPlaying fromFields(Object title, Object artist, Object playing, Object artwork) {
        if (!(title instanceof String) || !(artist instanceof String) || !(playing instanceof Boolean)) return null;
        String name = cleanLabel((String) title);
        if (name.isEmpty()) return null;
        Bitmap art = artwork instanceof Bitmap ? (Bitmap) artwork : null;
        if (art != null && (art.isRecycled() || art.getWidth() > MAX_ART_EDGE || art.getHeight() > MAX_ART_EDGE ||
                art.getAllocationByteCount() > MAX_ART_EDGE * MAX_ART_EDGE * 4)) art = null;
        return new ProfileNowPlaying(name, cleanLabel((String) artist), (boolean) playing, art);
    }

    private static String cleanLabel(String value) {
        StringBuilder out = new StringBuilder(MAX_LABEL_LENGTH);
        for (int offset = 0; offset < value.length() && offset < 512 && out.length() < MAX_LABEL_LENGTH;) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT ||
                    Character.getType(cp) == Character.SURROGATE) continue;
            if (out.length() + Character.charCount(cp) > MAX_LABEL_LENGTH) break;
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
    }
}

package com.example.headswap.panel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.headswap.desk.component.api.ProfileContract;
import com.headswap.desk.component.api.ProfileNowPlaying;
import com.headswap.desk.component.api.ProfileSession;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Media pane of the HeadSwap reference dashboard. The host owns the adjacent application. */
public final class PanelActivity extends Activity {
    private static final long REFRESH_MS = 2000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private MusicCard card;
    private ProfileSession session;
    private boolean visible;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        card = new MusicCard(this);
        setContentView(card);
        session = ProfileSession.from(getIntent());
        card.previous.setOnClickListener(v -> send(ProfileContract.MEDIA_PREVIOUS));
        card.toggle.setOnClickListener(v -> send(ProfileContract.MEDIA_PLAY_PAUSE));
        card.next.setOnClickListener(v -> send(ProfileContract.MEDIA_NEXT));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        session = ProfileSession.from(intent);
        generation++;
        main.removeCallbacks(refresh);
        card.setEmpty(R.string.no_playback, R.string.start_player);
        if (visible) refresh();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        refresh();
    }

    @Override protected void onStop() {
        visible = false;
        generation++;
        main.removeCallbacks(refresh);
        card.setEmpty(R.string.no_playback, R.string.start_player);
        super.onStop();
    }

    private void refresh() {
        final ProfileSession current = session;
        if (current == null || !current.canUseMedia()) {
            boolean standalone = current == null;
            card.setEmpty(standalone ? R.string.media_unavailable : R.string.media_permission,
                    standalone ? R.string.session_hint : R.string.media_permission_hint);
            return;
        }
        final int token = generation;
        worker.execute(() -> {
            ProfileNowPlaying track = current.getNowPlaying();
            main.post(() -> {
                if (!visible || token != generation) return;
                if (track == null) {
                    card.setEmpty(R.string.no_playback, R.string.start_player);
                } else {
                    card.setTrack(track.getTitle(), track.getArtist(), track.getArtwork(),
                            track.isPlaying(), true, true, true);
                }
                main.postDelayed(refresh, REFRESH_MS);
            });
        });
    }

    private void send(int command) {
        final ProfileSession current = session;
        if (current == null || !visible || !current.canUseMedia()) return;
        final int token = generation;
        worker.execute(() -> {
            boolean accepted = current.sendMediaCommand(command);
            main.post(() -> {
                if (!visible || token != generation) return;
                if (!accepted) Toast.makeText(this, R.string.transport_unsupported, Toast.LENGTH_SHORT).show();
                // Supersede any older snapshot that was already queued before this command.
                generation++;
                main.removeCallbacks(refresh);
                refresh();
            });
        });
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}

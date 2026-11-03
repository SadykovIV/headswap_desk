package com.example.headswap.panel;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import com.headswap.desk.component.api.ProfileContract;
import com.headswap.desk.component.api.ProfileNowPlaying;
import com.headswap.desk.component.api.ProfileSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** A full-window profile. HeadSwap alone owns the adjacent app task and its input. */
public final class PanelActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::publishLayout;
    private final Runnable poll = this::readMedia;
    private StarterScreen screen;
    private ProfileSession session;
    private boolean started, ready, music = true, right, target;
    private int attempts, layoutRevision, choiceRevision;
    private volatile int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        if (state != null) {
            music = state.getBoolean("music", true);
            right = state.getBoolean("right");
            target = state.getBoolean("target");
        }
        screen = new StarterScreen(this, this::launch);
        screen.arrange(music, right);
        setContentView(screen.root);
        session = ProfileSession.from(getIntent());
        screen.home.setOnClickListener(v -> home());
        screen.back.setOnClickListener(v -> back());
        screen.catalog.setOnClickListener(v -> {
            ProfileSession current = session;
            if (current != null && current.canOpenCatalog()) request(current::openCatalog, true);
        });
        screen.music.setOnClickListener(v -> {
            music = !music;
            screen.arrange(music, right);
            changed();
        });
        screen.side.setOnClickListener(v -> {
            right = !right;
            screen.arrange(music, right);
            changed();
        });
        screen.playback.setOnClickListener(v -> {
            ProfileSession current = session;
            if (current != null && current.canUseMedia())
                request(() -> current.sendMediaCommand(ProfileContract.MEDIA_PLAY_PAUSE), false);
        });
        screen.dock.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (b - t != oldB - oldT) changed();
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("music", music);
        state.putBoolean("right", right);
        state.putBoolean("target", target);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        generation++;
        session = ProfileSession.from(intent);
        target = false;
        if (started) startSession();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            bars.hide(WindowInsets.Type.systemBars());
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        startSession();
    }

    private void startSession() {
        final int run = generation;
        worker.execute(() -> {
            List<StarterScreen.Entry> apps = StarterScreen.loadApps(this);
            main.post(() -> { if (started && generation == run) screen.showApps(apps); });
        });
        changed();
    }

    @Override protected void onStop() {
        started = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        screen.track.setText("");
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void home() {
        choiceRevision++;
        target = false;
        screen.status.setText("Выберите приложение для соседнего окна.");
        changed();
    }

    @Override @Deprecated public void onBackPressed() { back(); }
    private void back() {
        if (!target) { home(); return; }
        ProfileSession current = session;
        if (current != null) request(() -> current.sendDashboardAction(ProfileContract.DASH_BACK), false);
    }

    /** Wait for the actual dock to be measured and for HeadSwap to observe this provider on its
     * child display. A slow first frame can reject the first layout report. Home permits retry. */
    private void changed() {
        ready = false;
        layoutRevision++;
        attempts = 0;
        main.removeCallbacks(retry);
        main.removeCallbacks(poll);
        if (started) main.post(retry);
    }

    private void publishLayout() {
        if (!started) return;
        final ProfileSession current = session;
        if (getDisplay() == null || getDisplay().getDisplayId() <= 0 ||
                current == null || !current.canUseProfileChrome()) {
            setControls(false);
            screen.status.setText("Установите профиль и выберите его в HeadSwap на головном устройстве.");
            return;
        }
        final int height = screen.dock.getHeight();
        if (height == 0) return; // A layout listener schedules the report when actual pixels exist.
        final int run = generation, version = layoutRevision;
        final boolean showMusic = music, onRight = right, showTarget = target;
        worker.execute(() -> {
            if (generation != run) return;
            boolean accepted = current.setDashboardLayout(showMusic, onRight, showTarget, height);
            main.post(() -> {
                if (!started || run != generation || version != layoutRevision) return;
                ready = accepted;
                setControls(accepted);
                if (accepted) {
                    screen.status.setText("Выберите приложение для соседнего окна.");
                    readMedia();
                } else if (++attempts < 8) {
                    main.postDelayed(retry, 650);
                } else {
                    screen.status.setText("Сеанс недоступен. Нажмите «Домой», чтобы повторить.");
                }
            });
        });
    }

    private void setControls(boolean connected) {
        screen.catalog.setEnabled(connected && session.canOpenCatalog());
        screen.playback.setVisibility(session != null && session.canUseMedia() ? View.VISIBLE : View.GONE);
        screen.playback.setEnabled(connected && music);
        if (!connected || session == null || !session.canUseMedia())
            screen.track.setText("Доступ к музыке не предоставлен.");
    }

    private void launch(ComponentName targetApp) {
        ProfileSession current = session;
        if (current == null || !current.canLaunchApps()) {
            screen.status.setText("Хост не предоставил запуск приложений.");
            return;
        }
        request(() -> current.requestLaunch(targetApp), true);
    }

    private void request(BooleanSupplier command, boolean opensTarget) {
        if (!started || !ready) {
            screen.status.setText("Дождитесь готовности панели или нажмите «Домой».");
            return;
        }
        final int run = generation, choice = ++choiceRevision;
        worker.execute(() -> {
            if (generation != run) return;
            boolean queued = command.getAsBoolean();
            main.post(() -> {
                if (!started || run != generation || choice != choiceRevision) return;
                if (!queued) {
                    screen.status.setText("Запрос отклонён. Можно выбрать другое приложение или нажать «Домой».");
                } else if (opensTarget) {
                    // Queued is not proof that Android started the app; HeadSwap handles placement/consent.
                    target = true;
                    changed();
                }
            });
        });
    }

    private void readMedia() {
        final ProfileSession current = session;
        if (!started || !ready || !music || current == null || !current.canUseMedia()) return;
        final int run = generation, version = layoutRevision;
        worker.execute(() -> {
            if (generation != run) return;
            ProfileNowPlaying track = current.getNowPlaying();
            main.post(() -> {
                if (!started || generation != run || layoutRevision != version || !music) return;
                screen.track.setText(track == null ? "Нет активного трека" :
                        String.join("\n", track.getTitle(), track.getArtist()));
                main.postDelayed(poll, 2200);
            });
        });
    }
}

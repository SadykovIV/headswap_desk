package com.example.headswap.panel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-bleed artwork and persistent controls in the profile's own window. */
final class MusicCard extends FrameLayout {
    final ImageButton previous, toggle, next;
    private final ImageView cover, emptyIcon;
    private final TextView title, artist;
    private final Context context;

    MusicCard(Context context) {
        super(context);
        this.context = context;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff192b3a, 0xff14212f, 0xff101622}));
        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(cover, new LayoutParams(-1, -1));
        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x85202939, 0x30101626, 0xaf0b1221, 0xdf070d18}));
        addView(shade, new LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(4), dp(4), 0);
        addView(top, new LayoutParams(-1, dp(56), Gravity.TOP));
        TextView caption = text(context, 13, false);
        caption.setText(R.string.music);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.10f);
        top.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER);
        details.setPadding(dp(14), 0, dp(14), 0);
        LayoutParams center = new LayoutParams(-1, -1, Gravity.CENTER);
        center.topMargin = dp(60);
        center.bottomMargin = dp(70);
        addView(details, center);
        emptyIcon = new ImageView(context);
        emptyIcon.setImageResource(R.drawable.dash_note);
        emptyIcon.setColorFilter(0xff8faec0);
        LinearLayout.LayoutParams noteSize = new LinearLayout.LayoutParams(dp(30), dp(30));
        noteSize.bottomMargin = dp(12);
        details.addView(emptyIcon, noteSize);
        title = text(context, 23, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(3);
        details.addView(title, -1, -2);
        artist = text(context, 15, false);
        artist.setTextColor(0xffc5d0da);
        artist.setGravity(Gravity.CENTER);
        artist.setMaxLines(3);
        artist.setPadding(0, dp(5), 0, 0);
        details.addView(artist, -1, -2);

        LinearLayout transport = new LinearLayout(context);
        transport.setGravity(Gravity.CENTER);
        LayoutParams bottom = new LayoutParams(-1, dp(62), Gravity.BOTTOM);
        bottom.bottomMargin = dp(8);
        addView(transport, bottom);
        previous = icon(context, R.drawable.dash_previous, R.string.previous);
        toggle = icon(context, R.drawable.dash_play, R.string.play);
        next = icon(context, R.drawable.dash_next, R.string.next);
        transport.addView(previous, square());
        transport.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        transport.addView(next, square());
        setEmpty(R.string.no_playback, R.string.start_player);
    }

    void setTrack(String track, String performer, Bitmap art, boolean playing,
                  boolean prevEnabled, boolean toggleEnabled, boolean nextEnabled) {
        title.setText(track == null || track.isBlank() ? context.getString(R.string.unknown_track) : track);
        artist.setText(performer);
        cover.setImageBitmap(art);
        emptyIcon.setVisibility(GONE);
        toggle.setImageResource(playing ? R.drawable.dash_pause : R.drawable.dash_play);
        toggle.setContentDescription(context.getString(playing ? R.string.pause : R.string.play));
        enableControl(previous, prevEnabled);
        enableControl(toggle, toggleEnabled);
        enableControl(next, nextEnabled);
    }

    void setEmpty(int label, int hint) {
        setTrack("", "", null, false, false, false, false);
        emptyIcon.setVisibility(VISIBLE);
        title.setText(label);
        artist.setText(hint);
    }

    private static void enableControl(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.30f);
    }

    static ImageButton icon(Context context, int resource, int description) {
        ImageButton view = new ImageButton(context);
        view.setImageResource(resource);
        view.setContentDescription(context.getString(description));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55ffffff), null, new ColorDrawable(Color.WHITE)));
        return view;
    }

    static TextView text(Context context, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams square() {
        return new LinearLayout.LayoutParams(dp(46), dp(48));
    }
}

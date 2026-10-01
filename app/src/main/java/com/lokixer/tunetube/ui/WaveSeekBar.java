package com.lokixer.tunetube.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.lokixer.tunetube.R;

import androidx.core.content.ContextCompat;

/**
 * A big seek bar: the part already played is a moving wave, the rest is a straight line.
 * The wave flattens out smoothly when the music is paused.
 */
public class WaveSeekBar extends View {

    public interface Listener {
        void onSeekStart();
        void onSeekPreview(long positionMs);
        void onSeekEnd(long positionMs);
    }

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF thumbRect = new RectF();

    private final float density;
    private final float strokeW, maxAmp, wavelength, thumbW, thumbH;

    private float progress, amplitude, phase;
    private long duration, position;
    private boolean dragging, waving;
    private ValueAnimator phaseAnim, ampAnim;
    private Listener listener;

    public WaveSeekBar(Context context) {
        this(context, null);
    }

    public WaveSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        strokeW = 7 * density;
        maxAmp = 5 * density;
        wavelength = 40 * density;
        thumbW = 6 * density;
        thumbH = 36 * density;

        int accent = ContextCompat.getColor(context, R.color.accent);

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setStrokeWidth(strokeW);
        wavePaint.setColor(accent);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(strokeW);
        trackPaint.setColor(accent);
        trackPaint.setAlpha(70);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(accent);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Ignored while the user is dragging the bar. */
    public void setPosition(long positionMs, long durationMs) {
        if (dragging) return;
        duration = durationMs;
        position = positionMs;
        progress = durationMs > 0 ? Math.max(0f, Math.min(1f, (float) positionMs / durationMs)) : 0f;
        invalidate();
    }

    public void setWaving(boolean shouldWave) {
        if (waving == shouldWave) return;
        waving = shouldWave;
        animateAmplitude(shouldWave ? maxAmp : 0f);
        if (shouldWave) startPhase();
    }

    private void animateAmplitude(final float target) {
        if (ampAnim != null) ampAnim.cancel();
        ampAnim = ValueAnimator.ofFloat(amplitude, target);
        ampAnim.setDuration(400);
        ampAnim.addUpdateListener(a -> {
            amplitude = (float) a.getAnimatedValue();
            invalidate();
        });
        ampAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (target == 0f && !waving) stopPhase();
            }
        });
        ampAnim.start();
    }

    private void startPhase() {
        if (!isAttachedToWindow()) return;
        if (phaseAnim != null && phaseAnim.isRunning()) return;
        phaseAnim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        phaseAnim.setDuration(1600);
        phaseAnim.setRepeatCount(ValueAnimator.INFINITE);
        phaseAnim.setInterpolator(new LinearInterpolator());
        phaseAnim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        phaseAnim.start();
    }

    private void stopPhase() {
        if (phaseAnim != null) {
            phaseAnim.cancel();
            phaseAnim = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (waving) startPhase();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPhase();
        if (ampAnim != null) ampAnim.cancel();
        super.onDetachedFromWindow();
    }

    private float leftEdge() {
        return thumbW / 2f + strokeW / 2f;
    }

    private float rightEdge() {
        return getWidth() - leftEdge();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cy = getHeight() / 2f;
        float left = leftEdge();
        float right = rightEdge();
        float px = left + (right - left) * progress;

        // Remaining part: a plain line
        canvas.drawLine(px, cy, right, cy, trackPaint);

        // Played part: the wave
        path.reset();
        float step = 2 * density;
        float k = (float) (2 * Math.PI / wavelength);
        path.moveTo(left, cy + amplitude * (float) Math.sin(-phase));
        for (float x = left + step; x < px; x += step) {
            path.lineTo(x, cy + amplitude * (float) Math.sin(k * (x - left) - phase));
        }
        path.lineTo(px, cy + amplitude * (float) Math.sin(k * (px - left) - phase));
        canvas.drawPath(path, wavePaint);

        // Thumb
        float h = dragging ? thumbH * 1.15f : thumbH;
        thumbRect.set(px - thumbW / 2f, cy - h / 2f, px + thumbW / 2f, cy + h / 2f);
        canvas.drawRoundRect(thumbRect, thumbW / 2f, thumbW / 2f, thumbPaint);
    }

    private void updateFromTouch(float x) {
        float left = leftEdge();
        float span = rightEdge() - left;
        progress = span > 0 ? Math.max(0f, Math.min(1f, (x - left) / span)) : 0f;
        position = (long) (progress * duration);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (duration <= 0) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(e.getX());
                if (listener != null) {
                    listener.onSeekStart();
                    listener.onSeekPreview(position);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateFromTouch(e.getX());
                    if (listener != null) listener.onSeekPreview(position);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    updateFromTouch(e.getX());
                    dragging = false;
                    invalidate();
                    if (listener != null) listener.onSeekEnd(position);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}

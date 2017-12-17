package net.smilfinken.timeoclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.os.ConfigurationCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class TimeOClock extends CanvasWatchFaceService {
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MILLISECONDS.toMillis(50);
    private static final int MSG_UPDATE_TIME = 0;

    private static final String THEME_COLOR = "#AAFFB020";
    private static final int BATTERY_GAUGE_WIDTH = 10;
    private static final int CHEVRON_OUTER_RADIUS = BATTERY_GAUGE_WIDTH + 1;
    private static final double LOW_BATTERY_LIMIT = 0.1f;

    private final double CIRCLE_RADIANS = 2 * Math.PI;
    private final double RADIANS_TO_DEGREES = 180 / Math.PI;
    private final double ROTATION_OFFSET = - 0.25f * CIRCLE_RADIANS;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<TimeOClock.Engine> mEngineReference;

        public EngineHandler(TimeOClock.Engine reference) {
            mEngineReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            TimeOClock.Engine engine = mEngineReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Locale mLocale = ConfigurationCompat.getLocales(getResources().getConfiguration()).get(0);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;
        private boolean mMuteMode;
        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;
        private double mBatteryFraction;

        private float mCenterX;
        private float mCenterY;
        private float mHandOuterRadius;
        private int mHandBitmapDiameter;

        private Paint mChevronPaint;
        private Paint mHandPaint;
        private Paint mHandTextPaint;
        private Paint mAmbientTextPaint;
        private Paint mAmbientBatteryPaint;

        private Bitmap mChevronTop;
        private Bitmap mChevron;
        private Bitmap mHandBitmapThemed;
        private Bitmap mBatteryIconBitmap;

        private int mHandBitmapRadius;
        private double mCenterOfHandRadius;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(TimeOClock.this)
                .setAcceptsTapEvents(true)
                .build());

            mCalendar = Calendar.getInstance();

            initializeWatchFace();
        }

        private Bitmap tintImage(Bitmap bitmap, int color) {
            Paint paint = new Paint();
            paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            Bitmap result = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            return result;
        }

        private void initializeWatchFace() {
            mChevron = BitmapFactory.decodeResource(getResources(), R.drawable.chevron);
            mChevronTop = BitmapFactory.decodeResource(getResources(), R.drawable.chevron_top);
            mChevronPaint = new Paint();
            mChevronPaint.setAntiAlias(true);
            mChevronPaint.setColor(Color.WHITE);

            mHandPaint = new Paint();
            mHandPaint.setAntiAlias(true);
            mHandPaint.setDither(true);

            mHandTextPaint = new Paint();
            mHandTextPaint.setColor(Color.BLACK);
            mHandTextPaint.setAntiAlias(true);

            mAmbientTextPaint = new Paint();
            mAmbientTextPaint.setColor(Color.GRAY);
            mAmbientTextPaint.setAntiAlias(true);

            mAmbientBatteryPaint = new Paint();
            mAmbientBatteryPaint.setAntiAlias(true);
            mAmbientBatteryPaint.setColorFilter(new PorterDuffColorFilter(0x8F000000, PorterDuff.Mode.DARKEN));
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);

            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode;
                invalidate();
            }
        }

        private int clampValue(int value, int min, int max) {
            return Math.min(max, Math.max(min, value));
        }

        private Bitmap createThemedBitmap(int width) {
            final int diameter = clampValue(Math.round(width / 5f), 48, 96);
            return tintImage(
                Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.gradient128),
                    diameter,
                    diameter,
                    false),
                Color.parseColor(THEME_COLOR)
            );
        }

        private Bitmap createScaledBatteryIconBitmap(int diameter) {
            final Bitmap batteryIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.lowbattery200);
            final float ratio = (float)batteryIconBitmap.getHeight() / (float)batteryIconBitmap.getWidth();
            return Bitmap.createScaledBitmap(batteryIconBitmap, diameter, Math.round(diameter * ratio), false);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            mCenterX = width / 2f;
            mCenterY = height / 2f;
            mHandOuterRadius = (mCenterX + mCenterY) / 2f - BATTERY_GAUGE_WIDTH;

            mHandBitmapThemed = createThemedBitmap(width);

            mHandBitmapDiameter = mHandBitmapThemed.getWidth();
            mHandBitmapRadius = mHandBitmapDiameter / 2;
            mCenterOfHandRadius = mHandOuterRadius - mHandBitmapRadius;

            final float textSize = mHandBitmapDiameter * 0.4f;
            mHandTextPaint.setTextSize(textSize);
            mAmbientTextPaint.setTextSize(textSize * 1.5f);

            mBatteryIconBitmap = createScaledBatteryIconBitmap(mHandBitmapDiameter);
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    //Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT).show();
                    Log.d("onTapCommand", getResources().getString(R.string.message));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            updateBatteryStatus();

            drawBackground(canvas);
            if (!mAmbient) {
                drawBatteryGauge(canvas);
                drawChevrons(canvas);
                drawActive(canvas);
            } else {
                drawAmbient(canvas);
            }
        }

        private void updateBatteryStatus() {
            final Intent batteryStatus = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            try {
                final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                mBatteryFraction = level / (float)scale;
            } catch(java.lang.NullPointerException e) {
                mBatteryFraction = 1f;
            }
        }

        private void drawBackground(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
        }

        private void drawBatteryGauge(Canvas canvas) {
            double arcZero = ROTATION_OFFSET * RADIANS_TO_DEGREES;
            double arcSweep = (mBatteryFraction * CIRCLE_RADIANS) * RADIANS_TO_DEGREES;

            Paint batteryGaugePaint = new Paint();
            batteryGaugePaint.setAntiAlias(true);
            batteryGaugePaint.setColor(Color.parseColor(THEME_COLOR));
            batteryGaugePaint.setStrokeWidth(BATTERY_GAUGE_WIDTH);
            batteryGaugePaint.setStyle(Paint.Style.STROKE);
            canvas.drawArc(0, 0, canvas.getWidth(), canvas.getHeight(), (float) arcZero, (float) arcSweep, false, batteryGaugePaint);
        }

        private void drawChevrons(Canvas canvas) {
            final int circleDegrees = 360;
            final int chevronCount = 12;
            final int step = circleDegrees / chevronCount;
            canvas.save();
            canvas.drawBitmap(mChevronTop, mCenterX - mChevronTop.getWidth() / 2 , CHEVRON_OUTER_RADIUS, mChevronPaint);
            for (int i = 1; i < chevronCount; i++) {
                canvas.rotate(step, mCenterX, mCenterY);
                if (i % 3 == 0) {
                    canvas.drawBitmap(mChevron, mCenterX - mChevron.getWidth() / 2, CHEVRON_OUTER_RADIUS, mChevronPaint);
                } else {
                    canvas.drawLine(mCenterX, CHEVRON_OUTER_RADIUS, mCenterX, CHEVRON_OUTER_RADIUS + mChevron.getHeight(), mChevronPaint);
                }
            }
            canvas.restore();
        }

        private void drawHand(Canvas canvas, float x, float y, Bitmap bitmap, String text) {
            final float bitmapCenterX = bitmap.getWidth() / 2;
            final float bitmapCenterY = bitmap.getHeight() / 2;
            final float textX = bitmapCenterX - mHandTextPaint.measureText(text) / 2f;
            final float textY = bitmapCenterY + mHandTextPaint.getTextSize() / 2.8f;
            Bitmap hand = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas handCanvas = new Canvas(hand);
            handCanvas.drawBitmap(bitmap, 0, 0, mHandPaint);
            handCanvas.drawText(text, textX, textY, mHandTextPaint);

            final float handX = x - bitmapCenterX;
            final float handY = y - bitmapCenterY;
            canvas.drawBitmap(hand, handX, handY, mHandPaint);
        }

        private double handX(double theta, double offset) {
            return mCenterX + (mCenterOfHandRadius - offset) * Math.cos(theta);
        }

        private double handY(double theta, double offset) {
            return mCenterY + (mCenterOfHandRadius - offset) * Math.sin(theta);
        }

        private double handDistance(double thetaA, double thetaB) {
            PointF handA = new PointF((float)handX(thetaA, 0), (float)handY(thetaA, 0));
            PointF handB = new PointF((float)handX(thetaB, 0), (float)handY(thetaB, 0));
            final double diff = Math.max(0, mHandBitmapDiameter - Math.sqrt(Math.pow(handA.x - handB.x, 2) + Math.pow(handA.y - handB.y, 2)));
            if (diff > 0) {
                final double displacementFraction = Math.sqrt(1 - Math.pow(1 - diff / (mHandBitmapDiameter), 2));
                return Math.ceil(displacementFraction * mHandBitmapDiameter);
            } else {
                return 0;
            }
        }

        private void drawActive(Canvas canvas) {
            final int hours = mCalendar.get(Calendar.HOUR);
            final int minutes = mCalendar.get(Calendar.MINUTE);
            final int seconds = mCalendar.get(Calendar.SECOND);
            final int milliseconds = mCalendar.get(Calendar.MILLISECOND);

            final String hoursText = String.format(mLocale, "%02d:", mCalendar.get(Calendar.HOUR_OF_DAY));
            final String minutesText = String.format(mLocale, "%02d", minutes);
            final String secondsText = String.format(mLocale, ":%02d", seconds);

            final double secondsTheta = ((seconds * 1000 + milliseconds) / (1000f * 60f)) * CIRCLE_RADIANS + ROTATION_OFFSET;
            final double secondX = handX(secondsTheta, 0);
            final double secondY = handY(secondsTheta, 0);
            drawHand(canvas, (float)secondX, (float)secondY, mHandBitmapThemed, secondsText);

            final double minutesTheta = ((minutes * 60 + seconds) / (60f * 60f)) * CIRCLE_RADIANS + ROTATION_OFFSET;
            final double minuteHandOffset = handDistance(secondsTheta, minutesTheta);
            final double minuteX = handX(minutesTheta, minuteHandOffset);
            final double minuteY = handY(minutesTheta, minuteHandOffset);
            drawHand(canvas, (float)minuteX, (float)minuteY, mHandBitmapThemed, minutesText);

            final double hoursTheta = ((hours * 60 * 60 + minutes * 60 + seconds) / (12f * 60f * 60f)) * CIRCLE_RADIANS + ROTATION_OFFSET;
            final double hourHandOffset = handDistance(hoursTheta, minutesTheta) + handDistance(hoursTheta, secondsTheta);
            final double hourX = handX(hoursTheta, hourHandOffset);
            final double hourY = handY(hoursTheta, hourHandOffset);
            drawHand(canvas, (float)hourX, (float)hourY, mHandBitmapThemed, hoursText);
        }

        private void drawAmbient(Canvas canvas) {
            final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:MM", mLocale);
            final String timeText = timeFormat.format(mCalendar.getTime()); //String.format(mLocale, "%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));
            final float timeTextXOffset = mAmbientTextPaint.measureText(timeText) / 2f;
            final float timeTextYOffset = 5; //mAmbientTextPaint.getTextSize();
            canvas.drawText(timeText, mCenterX - timeTextXOffset, mCenterY - timeTextYOffset, mAmbientTextPaint);

            final SimpleDateFormat dateFormat = new SimpleDateFormat("E d", mLocale);
            final String dateText = dateFormat.format(mCalendar.getTime()); //String.format(mLocale, "%02d %s", mCalendar.get(Calendar.DAY_OF_MONTH), "Sunday");
            final float dateTextXOffset = mAmbientTextPaint.measureText(dateText) / 2f;
            final float dateTextYOffset = mAmbientTextPaint.getTextSize() - 8;
            canvas.drawText(dateText, mCenterX - dateTextXOffset, mCenterY + dateTextYOffset, mAmbientTextPaint);

            if (mBatteryFraction < LOW_BATTERY_LIMIT) {
                final float batteryIconXOffset = mBatteryIconBitmap.getWidth() / 2f;
                final float batteryIconYOffset = mBatteryIconBitmap.getHeight() / 2f + timeTextYOffset;
                canvas.drawBitmap(mBatteryIconBitmap, mCenterX - batteryIconXOffset, mCenterY + batteryIconYOffset, mAmbientBatteryPaint);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            TimeOClock.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            TimeOClock.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}

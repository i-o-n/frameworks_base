/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.TimeZone;

public class KeyguardStatusView extends GridLayout implements
        ConfigurationController.ConfigurationListener {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;

    private final LockPatternUtils mLockPatternUtils;
    private final IActivityManager mIActivityManager;

    private LinearLayout mStatusViewContainer;
    private TextView mLogoutView;
    private KeyguardClockSwitch mClockView;
    private TextView mOwnerInfo;
    private TextClock mDefaultClockView;
    private KeyguardSliceView mKeyguardSlice;
    private View mNotificationIcons;
    private View mKeyguardSliceView;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;

    private boolean mPulsing;
    private float mDarkAmount = 0;
    private int mTextColor;

    /**
     * Bottom margin that defines the margin between bottom of smart space and top of notification
     * icons on AOD.
     */
    private int mIconTopMargin;
    private int mIconTopMarginWithHeader;
    private boolean mShowingHeader;

    private int mClockSelection;
    private int mDateSelection;

    // Date styles paddings
    private int mDateVerPadding;
    private int mDateHorPadding;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refreshTime();
        }

        @Override
        public void onTimeZoneChanged(TimeZone timeZone) {
            updateTimeZone(timeZone);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                updateOwnerInfo();
                updateLogoutView();
                mClockView.refreshLockFont();
                refreshLockDateFont();
                mClockView.refreshclocksize();
                mKeyguardSlice.refreshdatesize();
                refreshOwnerInfoSize();
                refreshOwnerInfoFont();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refreshFormat();
            updateOwnerInfo();
            updateLogoutView();
            mClockView.refreshLockFont();
            refreshLockDateFont();
            mClockView.refreshclocksize();
            mKeyguardSlice.refreshdatesize();
            updateDateStyles();
            refreshOwnerInfoSize();
            refreshOwnerInfoFont();
        }

        @Override
        public void onLogoutEnabledChanged() {
            updateLogoutView();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mIActivityManager = ActivityManager.getService();
        mLockPatternUtils = new LockPatternUtils(getContext());
        mHandler = new Handler(Looper.myLooper());
        onDensityOrFontScaleChanged();
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mClockView.hasCustomClock();
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    public void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        mClockView.setHasVisibleNotifications(hasVisibleNotifications);
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, "Schedule setEnableMarquee: " + (enabled ? "Enable" : "Disable"));
        if (enabled) {
            if (mPendingMarqueeStart == null) {
                mPendingMarqueeStart = () -> {
                    setEnableMarqueeImpl(true);
                    mPendingMarqueeStart = null;
                };
                mHandler.postDelayed(mPendingMarqueeStart, MARQUEE_DELAY_MS);
            }
        } else {
            if (mPendingMarqueeStart != null) {
                mHandler.removeCallbacks(mPendingMarqueeStart);
                mPendingMarqueeStart = null;
            }
            setEnableMarqueeImpl(false);
        }
    }

    private void setEnableMarqueeImpl(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStatusViewContainer = findViewById(R.id.status_view_container);
        mLogoutView = findViewById(R.id.logout);
        mNotificationIcons = findViewById(R.id.clock_notification_icon_container);
        if (mLogoutView != null) {
            mLogoutView.setOnClickListener(this::onLogoutClicked);
        }

        mClockView = findViewById(R.id.keyguard_clock_container);
        mClockView.setShowCurrentUserTime(true);
        mOwnerInfo = findViewById(R.id.owner_info);
        mKeyguardSlice = findViewById(R.id.keyguard_status_area);
        mKeyguardSliceView = findViewById(R.id.keyguard_status_area);
        mTextColor = mClockView.getCurrentTextColor();

        mClockView.refreshLockFont();
        refreshLockDateFont();
        mClockView.refreshclocksize();
        updateDateStyles();
        refreshOwnerInfoSize();
        refreshOwnerInfoFont();
        mKeyguardSlice.refreshdatesize();
        mKeyguardSlice.setContentChangeListener(this::onSliceContentChanged);
        onSliceContentChanged();

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refreshFormat();
        updateOwnerInfo();
        updateLogoutView();
        updateDark();
        updateSettings();
    }

    public KeyguardSliceView getKeyguardSliceView() {
        return mKeyguardSlice;
    }

    /**
     * Moves clock, adjusting margins when slice content changes.
     */
    private void onSliceContentChanged() {
        final boolean hasHeader = mKeyguardSlice.hasHeader();
        mClockView.setKeyguardShowingHeader(hasHeader);
        if (mShowingHeader == hasHeader) {
            return;
        }
        mShowingHeader = hasHeader;
        if (mNotificationIcons != null) {
            // Update top margin since header has appeared/disappeared.
            MarginLayoutParams params = (MarginLayoutParams) mNotificationIcons.getLayoutParams();
            params.setMargins(params.leftMargin,
                    hasHeader ? mIconTopMarginWithHeader : mIconTopMargin,
                    params.rightMargin,
                    params.bottomMargin);
            mNotificationIcons.setLayoutParams(params);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutOwnerInfo();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        if (mClockView != null) {
            mClockView.refreshclocksize();
        }
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_18));
        }
        loadBottomMargin();
    }

    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSlice.refresh();
    }

    private void refreshTime() {
        mClockView.refresh();

        if (mClockSelection == 0) {
            mClockView.setFormat12Hour(Patterns.clockView12);
            mClockView.setFormat24Hour(Patterns.clockView24);
        } else if (mClockSelection == 1) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong>:mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong>:mm"));
        } else if (mClockSelection == 2) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">h:mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk:m</font>"));
        } else if (mClockSelection == 3) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">h</font>:mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk</font>:mm"));
        } else if (mClockSelection == 4) {
            mClockView.setFormat12Hour(Html.fromHtml("h<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">:mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("kk<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">:mm</font>"));
        } else if (mClockSelection == 5) {
            mClockView.setFormat12Hour("hh\nmm");
            mClockView.setFormat24Hour("kk\nmm");
        } else if (mClockSelection == 6) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>hh</strong><br>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><br>mm"));
        } else if (mClockSelection == 7) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">hh</font><br>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.accent_device_default_light) + ">kk</font><br>mm"));
        } else if (mClockSelection == 8) {
            mClockView.setFormat12Hour(Html.fromHtml("hh<br><font color=" + getResources().getColor(R.color.accent_device_default_light) + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("kk<br><font color=" + getResources().getColor(R.color.accent_device_default_light) + ">mm</font>"));
        }
    }

    private void updateTimeZone(TimeZone timeZone) {
        mClockView.onTimeZoneChanged(timeZone);
    }

    private int getLockDateFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_DATE_FONTS, 0);
    }

    private int getOwnerInfoFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_OWNERINFO_FONTS, 0);
    }

    private int getOwnerInfoSize() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKOWNER_FONT_SIZE, 18);
    }

    private void refreshFormat() {
        Patterns.update(mContext);
        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    public int getLogoutButtonHeight() {
        if (mLogoutView == null) {
            return 0;
        }
        return mLogoutView.getVisibility() == VISIBLE ? mLogoutView.getHeight() : 0;
    }

    private void updateDateStyles() {
        final ContentResolver resolver = getContext().getContentResolver();

        mDateSelection = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCKSCREEN_DATE_SELECTION, 0, UserHandle.USER_CURRENT);

        switch (mDateSelection) {
            case 0: // default
            default:
                mKeyguardSlice.setViewBackgroundResource(0);
                mDateVerPadding = 0;
                mDateHorPadding = 0;
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 1: // semi-transparent box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_box_str_border));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 2: // semi-transparent box (round)
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_border));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 3: // Q-Now Playing background
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.ambient_indication_pill_background));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.q_nowplay_pill_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.q_nowplay_pill_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.05f, false);
                break;
            case 4: // accent box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_accent));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 5: // accent box transparent
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_accent), 160);
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 6: // gradient box
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_gradient));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.15f, true);
                break;
            case 7: // Dark Accent border
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_borderacc));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.08f, true);
                break;
            case 8: // Dark Gradient border
                mKeyguardSlice.setViewBackground(getResources().getDrawable(R.drawable.date_str_bordergrad));
                mDateHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                mDateVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                mKeyguardSlice.setViewPadding(mDateHorPadding,mDateVerPadding,mDateHorPadding,mDateVerPadding);
                mKeyguardSlice.setViewsTextStyles(0.08f, true);
                break;
        }
    }

    private void refreshLockDateFont() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockDateFont = isPrimary ? getLockDateFont() : 0;
        if (lockDateFont == 0) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (lockDateFont == 1) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (lockDateFont == 2) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (lockDateFont == 3) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (lockDateFont == 4) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (lockDateFont == 5) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (lockDateFont == 6) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (lockDateFont == 7) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (lockDateFont == 8) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (lockDateFont == 9) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (lockDateFont == 10) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (lockDateFont == 11) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (lockDateFont == 12) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (lockDateFont == 13) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (lockDateFont == 14) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (lockDateFont == 15) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (lockDateFont == 16) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (lockDateFont == 17) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (lockDateFont == 18) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("abelreg", Typeface.NORMAL));
        }
        if (lockDateFont == 19) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("adamcg-pro", Typeface.NORMAL));
        }
        if (lockDateFont == 20) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("adventpro", Typeface.NORMAL));
        }
        if (lockDateFont == 21) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("alexana-neue", Typeface.NORMAL));
        }
        if (lockDateFont == 22) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("alien-league", Typeface.NORMAL));
        }
        if (lockDateFont == 23) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("archivonar", Typeface.NORMAL));
        }
        if (lockDateFont == 24) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("autourone", Typeface.NORMAL));
        }
        if (lockDateFont == 25) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("azedo-light", Typeface.NORMAL));
        }
        if (lockDateFont == 26) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("badscript", Typeface.NORMAL));
        }
        if (lockDateFont == 27) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("bignoodle-regular", Typeface.NORMAL));
        }
        if (lockDateFont == 28) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("biko", Typeface.NORMAL));
        }
        if (lockDateFont == 29) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("blern", Typeface.NORMAL));
        }
        if (lockDateFont == 30) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("cherryswash", Typeface.NORMAL));
        }
        if (lockDateFont == 31) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("cocon", Typeface.NORMAL));
        }
        if (lockDateFont == 32) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("codystar", Typeface.NORMAL));
        }
        if (lockDateFont == 33) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("fester", Typeface.NORMAL));
        }
        if (lockDateFont == 34) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("fox-and-cat", Typeface.NORMAL));
        }
        if (lockDateFont == 35) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("ginora-sans", Typeface.NORMAL));
        }
        if (lockDateFont == 36) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("gobold-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 37) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("ibmplex-mono", Typeface.NORMAL));
        }
        if (lockDateFont == 38) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("inkferno", Typeface.NORMAL));
        }
        if (lockDateFont == 39) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("instruction", Typeface.NORMAL));
        }
        if (lockDateFont == 40) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("jacklane", Typeface.NORMAL));
        }
        if (lockDateFont == 41) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("jura-reg", Typeface.NORMAL));
        }
        if (lockDateFont == 42) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("kellyslab", Typeface.NORMAL));
        }
        if (lockDateFont == 43) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("metropolis1920", Typeface.NORMAL));
        }
        if (lockDateFont == 44) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("monad", Typeface.NORMAL));
        }
        if (lockDateFont == 45) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("neonneon", Typeface.NORMAL));
        }
        if (lockDateFont == 46) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("noir", Typeface.NORMAL));
        }
        if (lockDateFont == 47) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("northfont", Typeface.NORMAL));
        }
        if (lockDateFont == 48) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("outrun-future", Typeface.NORMAL));
        }
        if (lockDateFont == 49) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("pompiere", Typeface.NORMAL));
        }
        if (lockDateFont == 50) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("qontra", Typeface.NORMAL));
        }
        if (lockDateFont == 51) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("raleway-light", Typeface.NORMAL));
        }
        if (lockDateFont == 52) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("reemkufi", Typeface.NORMAL));
        }
        if (lockDateFont == 53) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("riviera", Typeface.NORMAL));
        }
        if (lockDateFont == 54) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 55) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("satisfy", Typeface.NORMAL));
        }
        if (lockDateFont == 56) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("seaweedsc", Typeface.NORMAL));
        }
        if (lockDateFont == 57) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sedgwick-ave", Typeface.NORMAL));
        }
        if (lockDateFont == 58) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 59) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("source-sans-pro", Typeface.NORMAL));
        }
        if (lockDateFont == 60) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("themeable-clock", Typeface.NORMAL));
        }
        if (lockDateFont == 61) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("the-outbox", Typeface.NORMAL));
        }
        if (lockDateFont == 62) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("unionfont", Typeface.NORMAL));
        }
        if (lockDateFont == 63) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("vibur", Typeface.NORMAL));
        }
        if (lockDateFont == 64) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("voltaire", Typeface.NORMAL));
        }
    }

    public void refreshOwnerInfoSize() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int ownerInfoSize = isPrimary ? getOwnerInfoSize() : 18;

        switch (ownerInfoSize) {
            case 10:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_10));
                break;
            case 11:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_11));
                break;
            case 12:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_12));
                break;
            case 13:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_13));
                break;
            case 14:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_14));
                break;
            case 15:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_15));
                break;
            case 16:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_16));
                break;
            case 17:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_17));
                break;
            case 18:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_18));
                break;
            case 19:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_19));
                break;
            case 20:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_20));
                break;
            case 21:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_21));
                break;
            case 22:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_22));
                break;
            case 23:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_23));
                break;
            case 24:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_24));
                break;
            case 25:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_25));
                break;
            case 26:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_26));
                break;
            case 27:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_27));
                break;
            case 28:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_28));
                break;
            case 29:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_29));
                break;
            case 30:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_30));
                break;
            case 31:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_31));
                break;
            case 32:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_32));
                break;
            case 33:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_33));
                break;
            case 34:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_34));
                break;
            case 35:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_35));
                break;
            case 36:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_36));
                break;
            case 37:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_37));
                break;
            case 38:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_38));
                break;
            case 39:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_39));
                break;
            case 40:
                mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_40));
                break;
        }
    }

    private void refreshOwnerInfoFont() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int ownerinfoFont = isPrimary ? getOwnerInfoFont() : 0;

        if (ownerinfoFont == 0) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (ownerinfoFont == 1) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (ownerinfoFont == 2) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (ownerinfoFont == 3) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (ownerinfoFont == 4) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (ownerinfoFont == 5) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (ownerinfoFont == 6) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (ownerinfoFont == 7) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (ownerinfoFont == 8) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (ownerinfoFont == 9) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (ownerinfoFont == 10) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (ownerinfoFont == 11) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (ownerinfoFont == 12) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (ownerinfoFont == 13) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (ownerinfoFont == 14) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (ownerinfoFont == 15) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (ownerinfoFont == 16) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (ownerinfoFont == 17) {
            mOwnerInfo.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (ownerinfoFont == 18) {
            mOwnerInfo.setTypeface(Typeface.create("abelreg", Typeface.NORMAL));
        }
        if (ownerinfoFont == 19) {
            mOwnerInfo.setTypeface(Typeface.create("adamcg-pro", Typeface.NORMAL));
        }
        if (ownerinfoFont == 20) {
            mOwnerInfo.setTypeface(Typeface.create("adventpro", Typeface.NORMAL));
        }
        if (ownerinfoFont == 21) {
            mOwnerInfo.setTypeface(Typeface.create("alexana-neue", Typeface.NORMAL));
        }
        if (ownerinfoFont == 22) {
            mOwnerInfo.setTypeface(Typeface.create("alien-league", Typeface.NORMAL));
        }
        if (ownerinfoFont == 23) {
            mOwnerInfo.setTypeface(Typeface.create("archivonar", Typeface.NORMAL));
        }
        if (ownerinfoFont == 24) {
            mOwnerInfo.setTypeface(Typeface.create("autourone", Typeface.NORMAL));
        }
        if (ownerinfoFont == 25) {
            mOwnerInfo.setTypeface(Typeface.create("azedo-light", Typeface.NORMAL));
        }
        if (ownerinfoFont == 26) {
            mOwnerInfo.setTypeface(Typeface.create("badscript", Typeface.NORMAL));
        }
        if (ownerinfoFont == 27) {
            mOwnerInfo.setTypeface(Typeface.create("bignoodle-regular", Typeface.NORMAL));
        }
        if (ownerinfoFont == 28) {
            mOwnerInfo.setTypeface(Typeface.create("biko", Typeface.NORMAL));
        }
        if (ownerinfoFont == 29) {
            mOwnerInfo.setTypeface(Typeface.create("blern", Typeface.NORMAL));
        }
        if (ownerinfoFont == 30) {
            mOwnerInfo.setTypeface(Typeface.create("cherryswash", Typeface.NORMAL));
        }
        if (ownerinfoFont == 31) {
            mOwnerInfo.setTypeface(Typeface.create("cocon", Typeface.NORMAL));
        }
        if (ownerinfoFont == 32) {
            mOwnerInfo.setTypeface(Typeface.create("codystar", Typeface.NORMAL));
        }
        if (ownerinfoFont == 33) {
            mOwnerInfo.setTypeface(Typeface.create("fester", Typeface.NORMAL));
        }
        if (ownerinfoFont == 34) {
            mOwnerInfo.setTypeface(Typeface.create("fox-and-cat", Typeface.NORMAL));
        }
        if (ownerinfoFont == 35) {
            mOwnerInfo.setTypeface(Typeface.create("ginora-sans", Typeface.NORMAL));
        }
        if (ownerinfoFont == 36) {
            mOwnerInfo.setTypeface(Typeface.create("gobold-sys", Typeface.NORMAL));
        }
        if (ownerinfoFont == 37) {
            mOwnerInfo.setTypeface(Typeface.create("ibmplex-mono", Typeface.NORMAL));
        }
        if (ownerinfoFont == 38) {
            mOwnerInfo.setTypeface(Typeface.create("inkferno", Typeface.NORMAL));
        }
        if (ownerinfoFont == 39) {
            mOwnerInfo.setTypeface(Typeface.create("instruction", Typeface.NORMAL));
        }
        if (ownerinfoFont == 40) {
            mOwnerInfo.setTypeface(Typeface.create("jacklane", Typeface.NORMAL));
        }
        if (ownerinfoFont == 41) {
            mOwnerInfo.setTypeface(Typeface.create("jura-reg", Typeface.NORMAL));
        }
        if (ownerinfoFont == 42) {
            mOwnerInfo.setTypeface(Typeface.create("kellyslab", Typeface.NORMAL));
        }
        if (ownerinfoFont == 43) {
            mOwnerInfo.setTypeface(Typeface.create("metropolis1920", Typeface.NORMAL));
        }
        if (ownerinfoFont == 44) {
            mOwnerInfo.setTypeface(Typeface.create("monad", Typeface.NORMAL));
        }
        if (ownerinfoFont == 45) {
            mOwnerInfo.setTypeface(Typeface.create("neonneon", Typeface.NORMAL));
        }
        if (ownerinfoFont == 46) {
            mOwnerInfo.setTypeface(Typeface.create("noir", Typeface.NORMAL));
        }
        if (ownerinfoFont == 47) {
            mOwnerInfo.setTypeface(Typeface.create("northfont", Typeface.NORMAL));
        }
        if (ownerinfoFont == 48) {
            mOwnerInfo.setTypeface(Typeface.create("outrun-future", Typeface.NORMAL));
        }
        if (ownerinfoFont == 49) {
            mOwnerInfo.setTypeface(Typeface.create("pompiere", Typeface.NORMAL));
        }
        if (ownerinfoFont == 50) {
            mOwnerInfo.setTypeface(Typeface.create("qontra", Typeface.NORMAL));
        }
        if (ownerinfoFont == 51) {
            mOwnerInfo.setTypeface(Typeface.create("raleway-light", Typeface.NORMAL));
        }
        if (ownerinfoFont == 52) {
            mOwnerInfo.setTypeface(Typeface.create("reemkufi", Typeface.NORMAL));
        }
        if (ownerinfoFont == 53) {
            mOwnerInfo.setTypeface(Typeface.create("riviera", Typeface.NORMAL));
        }
        if (ownerinfoFont == 54) {
            mOwnerInfo.setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
        }
        if (ownerinfoFont == 55) {
            mOwnerInfo.setTypeface(Typeface.create("satisfy", Typeface.NORMAL));
        }
        if (ownerinfoFont == 56) {
            mOwnerInfo.setTypeface(Typeface.create("seaweedsc", Typeface.NORMAL));
        }
        if (ownerinfoFont == 57) {
            mOwnerInfo.setTypeface(Typeface.create("sedgwick-ave", Typeface.NORMAL));
        }
        if (ownerinfoFont == 58) {
            mOwnerInfo.setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
        }
        if (ownerinfoFont == 59) {
            mOwnerInfo.setTypeface(Typeface.create("source-sans-pro", Typeface.NORMAL));
        }
        if (ownerinfoFont == 60) {
            mOwnerInfo.setTypeface(Typeface.create("themeable-clock", Typeface.NORMAL));
        }
        if (ownerinfoFont == 61) {
            mOwnerInfo.setTypeface(Typeface.create("the-outbox", Typeface.NORMAL));
        }
        if (ownerinfoFont == 62) {
            mOwnerInfo.setTypeface(Typeface.create("unionfont", Typeface.NORMAL));
        }
        if (ownerinfoFont == 63) {
            mOwnerInfo.setTypeface(Typeface.create("vibur", Typeface.NORMAL));
        }
        if (ownerinfoFont == 64) {
            mOwnerInfo.setTypeface(Typeface.create("voltaire", Typeface.NORMAL));
        }
    }

    public float getClockTextSize() {
        return mClockView.getTextSize();
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight The height available to position the clock.
     * @return Y position of clock.
     */
    public int getClockPreferredY(int totalHeight) {
        return mClockView.getPreferredY(totalHeight);
    }

    private void updateLogoutView() {
        if (mLogoutView == null) {
            return;
        }
        mLogoutView.setVisibility(shouldShowLogout() ? VISIBLE : GONE);
        // Logout button will stay in language of user 0 if we don't set that manually.
        mLogoutView.setText(mContext.getResources().getString(
                com.android.internal.R.string.global_action_logout));
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String info = mLockPatternUtils.getDeviceOwnerInfo();
        if (info == null) {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        mOwnerInfo.setText(info);
        updateDark();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onLocaleListChanged() {
        refreshFormat();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusView:");
        pw.println("  mOwnerInfo: " + (mOwnerInfo == null
                ? "null" : mOwnerInfo.getVisibility() == VISIBLE));
        pw.println("  mPulsing: " + mPulsing);
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        if (mLogoutView != null) {
            pw.println("  logout visible: " + (mLogoutView.getVisibility() == VISIBLE));
        }
        if (mClockView != null) {
            mClockView.dump(fd, pw, args);
        }
        if (mKeyguardSlice != null) {
            mKeyguardSlice.dump(fd, pw, args);
        }
    }

    private void loadBottomMargin() {
        mIconTopMargin = getResources().getDimensionPixelSize(R.dimen.widget_vertical_padding);
        mIconTopMarginWithHeader = getResources().getDimensionPixelSize(
                R.dimen.widget_vertical_padding_with_header);
    }

    private void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();

        mClockSelection = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT);
        final boolean mShowClock = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_CLOCK, 1, UserHandle.USER_CURRENT) == 1;

        mClockView = findViewById(R.id.keyguard_clock_container);
        mDefaultClockView = findViewById(R.id.default_clock_view);
        mClockView.setVisibility(mDarkAmount != 1
                ? (mShowClock ? View.VISIBLE : View.GONE) : View.VISIBLE);

        if (mClockSelection == 5 || mClockSelection == 6
                || mClockSelection == 7 || mClockSelection == 8)
            mDefaultClockView.setLineSpacing(0, 0.8f);

        updateDateStyles();
    }

    public void updateAll() {
        updateSettings();
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            cacheKey = key;
        }
    }

    public void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        mClockView.setDarkAmount(darkAmount);
        updateDark();
    }

    private void updateDark() {
        boolean dark = mDarkAmount == 1;
        if (mLogoutView != null) {
            mLogoutView.setAlpha(dark ? 0 : 1);
        }

        if (mOwnerInfo != null) {
            boolean hasText = !TextUtils.isEmpty(mOwnerInfo.getText());
            mOwnerInfo.setVisibility(hasText ? VISIBLE : GONE);
            layoutOwnerInfo();
        }

        final int blendedTextColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        mKeyguardSlice.setDarkAmount(mDarkAmount);
        mClockView.setTextColor(blendedTextColor);
        updateSettings();
    }

    private void layoutOwnerInfo() {
        if (mOwnerInfo != null && mOwnerInfo.getVisibility() != GONE) {
            // Animate owner info during wake-up transition
            mOwnerInfo.setAlpha(1f - mDarkAmount);

            float ratio = mDarkAmount;
            // Calculate how much of it we should crop in order to have a smooth transition
            int collapsed = mOwnerInfo.getTop() - mOwnerInfo.getPaddingTop();
            int expanded = mOwnerInfo.getBottom() + mOwnerInfo.getPaddingBottom();
            int toRemove = (int) ((expanded - collapsed) * ratio);
            setBottom(getMeasuredHeight() - toRemove);
            if (mNotificationIcons != null) {
                // We're using scrolling in order not to overload the translation which is used
                // when appearing the icons
                mNotificationIcons.setScrollY(toRemove);
            }
        } else if (mNotificationIcons != null){
            mNotificationIcons.setScrollY(0);
        }
    }

    public void setPulsing(boolean pulsing) {
        if (mPulsing == pulsing) {
            return;
        }
        mPulsing = pulsing;
    }

    private boolean shouldShowLogout() {
        return KeyguardUpdateMonitor.getInstance(mContext).isLogoutEnabled()
                && KeyguardUpdateMonitor.getCurrentUser() != UserHandle.USER_SYSTEM;
    }

    private void onLogoutClicked(View view) {
        int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
        try {
            mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
            mIActivityManager.stopUser(currentUserId, true /*force*/, null);
        } catch (RemoteException re) {
            Log.e(TAG, "Failed to logout user", re);
        }
    }
}

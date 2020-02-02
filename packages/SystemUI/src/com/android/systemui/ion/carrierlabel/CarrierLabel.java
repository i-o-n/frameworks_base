/*
 * Copyright (C) 2014-2015 The MoKee OpenSource Project
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

package com.android.systemui.ion.carrierlabel;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.util.ion.Utils;
import com.android.internal.telephony.TelephonyIntents;

import com.android.systemui.Dependency;
import com.android.systemui.ion.carrierlabel.SpnOverride;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import com.android.systemui.R;

public class CarrierLabel extends TextView implements DarkReceiver {

    private Context mContext;
    private boolean mAttached;
    private static boolean isCN;
    private int mCarrierFontSize = 14;
    private int mCarrierColor = 0xffffffff;
    private int mTintColor = Color.WHITE;

    private int mCarrierLabelFontStyle = FONT_NORMAL;
    public static final int FONT_NORMAL = 0;
    public static final int FONT_ITALIC = 1;
    public static final int FONT_BOLD = 2;
    public static final int FONT_BOLD_ITALIC = 3;
    public static final int FONT_LIGHT = 4;
    public static final int FONT_LIGHT_ITALIC = 5;
    public static final int FONT_THIN = 6;
    public static final int FONT_THIN_ITALIC = 7;
    public static final int FONT_CONDENSED = 8;
    public static final int FONT_CONDENSED_ITALIC = 9;
    public static final int FONT_CONDENSED_LIGHT = 10;
    public static final int FONT_CONDENSED_LIGHT_ITALIC = 11;
    public static final int FONT_CONDENSED_BOLD = 12;
    public static final int FONT_CONDENSED_BOLD_ITALIC = 13;
    public static final int FONT_MEDIUM = 14;
    public static final int FONT_MEDIUM_ITALIC = 15;
    public static final int FONT_BLACK = 16;
    public static final int FONT_BLACK_ITALIC = 17;

    Handler mHandler;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CARRIER_COLOR), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CARRIER_FONT_SIZE), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CARRIER_FONT_STYLE), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
	     updateColor();
	     updateSize();
	     updateStyle();
        }
    }

    public CarrierLabel(Context context) {
        this(context, null);
    }

    public CarrierLabel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarrierLabel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        updateNetworkName(true, null, false, null);
        mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateColor();
        updateSize();
        updateStyle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
            filter.addAction(Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(area, this, tint);
        if (mCarrierColor == 0xFFFFFFFF) {
            setTextColor(mTintColor);
        } else {
            setTextColor(mCarrierColor);
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.SPN_STRINGS_UPDATED_ACTION.equals(action)
                    || Intent.ACTION_CUSTOM_CARRIER_LABEL_CHANGED.equals(action)) {
                        updateNetworkName(intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, true),
                        intent.getStringExtra(TelephonyIntents.EXTRA_SPN),
                        intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(TelephonyIntents.EXTRA_PLMN));
                isCN = Utils.isChineseLanguage();
            }
        }
    };

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        final String str;
        final boolean plmnValid = showPlmn && !TextUtils.isEmpty(plmn);
        final boolean spnValid = showSpn && !TextUtils.isEmpty(spn);
        if (spnValid) {
            str = spn;
        } else if (plmnValid) {
            str = plmn;
        } else {
            str = "";
        }
        String customCarrierLabel = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.CUSTOM_CARRIER_LABEL, UserHandle.USER_CURRENT);
        if (!TextUtils.isEmpty(customCarrierLabel)) {
            setText(customCarrierLabel);
        } else {
            setText(TextUtils.isEmpty(str) ? getOperatorName() : str);
        }
    }

    private String getOperatorName() {
        String operatorName = getContext().getString(R.string.quick_settings_wifi_no_network);
        TelephonyManager telephonyManager = (TelephonyManager) getContext().getSystemService(
                Context.TELEPHONY_SERVICE);
        if (isCN) {
            String operator = telephonyManager.getNetworkOperator();
            if (TextUtils.isEmpty(operator)) {
                operator = telephonyManager.getSimOperator();
            }
            SpnOverride mSpnOverride = new SpnOverride();
            operatorName = mSpnOverride.getSpn(operator);
        } else {
            operatorName = telephonyManager.getNetworkOperatorName();
        }
        if (TextUtils.isEmpty(operatorName)) {
            operatorName = telephonyManager.getSimOperatorName();
        }
        return operatorName;
    }

    public void getFontStyle(int font) {
        switch (font) {
            case FONT_NORMAL:
            default:
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case FONT_ITALIC:
                setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case FONT_BOLD:
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case FONT_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case FONT_LIGHT:
                setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case FONT_THIN:
                setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case FONT_THIN_ITALIC:
                setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case FONT_CONDENSED:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_LIGHT:
                setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_BOLD:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case FONT_CONDENSED_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case FONT_MEDIUM:
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case FONT_MEDIUM_ITALIC:
                setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                break;
            case FONT_BLACK:
                setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case FONT_BLACK_ITALIC:
                setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                break;
            case 18:
                setTypeface(Typeface.create("abelreg", Typeface.NORMAL));
                break;
            case 19:
                setTypeface(Typeface.create("adamcg-pro", Typeface.NORMAL));
                break;
            case 20:
                setTypeface(Typeface.create("adventpro", Typeface.NORMAL));
                break;
            case 21:
                setTypeface(Typeface.create("alexana-neue", Typeface.NORMAL));
                break;
            case 22:
                setTypeface(Typeface.create("alien-league", Typeface.NORMAL));
                break;
            case 23:
                setTypeface(Typeface.create("archivonar", Typeface.NORMAL));
                break;
            case 24:
                setTypeface(Typeface.create("autourone", Typeface.NORMAL));
                break;
            case 25:
                setTypeface(Typeface.create("azedo-light", Typeface.NORMAL));
                break;
            case 26:
                setTypeface(Typeface.create("badscript", Typeface.NORMAL));
                break;
            case 27:
                setTypeface(Typeface.create("bignoodle-regular", Typeface.NORMAL));
                break;
            case 28:
                setTypeface(Typeface.create("biko", Typeface.NORMAL));
                break;
            case 29:
                setTypeface(Typeface.create("blern", Typeface.NORMAL));
                break;
            case 30:
                setTypeface(Typeface.create("cherryswash", Typeface.NORMAL));
                break;
            case 31:
                setTypeface(Typeface.create("cocon", Typeface.NORMAL));
                break;
            case 32:
                setTypeface(Typeface.create("codystar", Typeface.NORMAL));
                break;
            case 33:
                setTypeface(Typeface.create("fester", Typeface.NORMAL));
                break;
            case 34:
                setTypeface(Typeface.create("fox-and-cat", Typeface.NORMAL));
                break;
            case 35:
                setTypeface(Typeface.create("ginora-sans", Typeface.NORMAL));
                break;
            case 36:
                setTypeface(Typeface.create("gobold-sys", Typeface.NORMAL));
                break;
            case 37:
                setTypeface(Typeface.create("ibmplex-mono", Typeface.NORMAL));
                break;
            case 38:
                setTypeface(Typeface.create("inkferno", Typeface.NORMAL));
                break;
            case 39:
                setTypeface(Typeface.create("instruction", Typeface.NORMAL));
                break;
            case 40:
                setTypeface(Typeface.create("jacklane", Typeface.NORMAL));
                break;
            case 41:
                setTypeface(Typeface.create("jura-reg", Typeface.NORMAL));
                break;
            case 42:
                setTypeface(Typeface.create("kellyslab", Typeface.NORMAL));
                break;
            case 43:
                setTypeface(Typeface.create("metropolis1920", Typeface.NORMAL));
                break;
            case 44:
                setTypeface(Typeface.create("monad", Typeface.NORMAL));
                break;
            case 45:
                setTypeface(Typeface.create("neonneon", Typeface.NORMAL));
                break;
            case 46:
                setTypeface(Typeface.create("noir", Typeface.NORMAL));
                break;
            case 47:
                setTypeface(Typeface.create("northfont", Typeface.NORMAL));
                break;
            case 48:
                setTypeface(Typeface.create("outrun-future", Typeface.NORMAL));
                break;
            case 49:
                setTypeface(Typeface.create("pompiere", Typeface.NORMAL));
                break;
            case 50:
                setTypeface(Typeface.create("qontra", Typeface.NORMAL));
                break;
            case 51:
                setTypeface(Typeface.create("raleway-light", Typeface.NORMAL));
                break;
            case 52:
                setTypeface(Typeface.create("reemkufi", Typeface.NORMAL));
                break;
            case 53:
                setTypeface(Typeface.create("riviera", Typeface.NORMAL));
                break;
            case 54:
                setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
                break;
            case 55:
                setTypeface(Typeface.create("satisfy", Typeface.NORMAL));
                break;
            case 56:
                setTypeface(Typeface.create("seaweedsc", Typeface.NORMAL));
                break;
            case 57:
                setTypeface(Typeface.create("sedgwick-ave", Typeface.NORMAL));
                break;
            case 58:
                setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
                break;
            case 59:
                setTypeface(Typeface.create("source-sans-pro", Typeface.NORMAL));
                break;
            case 60:
                setTypeface(Typeface.create("themeable-clock", Typeface.NORMAL));
                break;
            case 61:
                setTypeface(Typeface.create("the-outbox", Typeface.NORMAL));
                break;
            case 62:
                setTypeface(Typeface.create("unionfont", Typeface.NORMAL));
                break;
            case 63:
                setTypeface(Typeface.create("vibur", Typeface.NORMAL));
                break;
            case 64:
                setTypeface(Typeface.create("voltaire", Typeface.NORMAL));
                break;
        }
    }

    private void updateColor() {
        mCarrierColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_COLOR, 0xffffffff);
        if (mCarrierColor == 0xFFFFFFFF) {
            setTextColor(mTintColor);
        } else {
            setTextColor(mCarrierColor);
        }
    }

    private void updateSize() {
        mCarrierFontSize = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_FONT_SIZE, 14);
        setTextSize(mCarrierFontSize);
    }

    private void updateStyle() {
        mCarrierLabelFontStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_FONT_STYLE, FONT_MEDIUM);
        getFontStyle(mCarrierLabelFontStyle);
    }
}

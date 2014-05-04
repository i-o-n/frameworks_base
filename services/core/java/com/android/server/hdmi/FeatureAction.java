/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.hdmi;

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Encapsulates a sequence of CEC/MHL command exchange for a certain feature.
 *
 * <p>Many CEC/MHL features are accomplished by CEC devices on the bus exchanging
 * more than one command. {@link FeatureAction} represents the life cycle of the communication,
 * manages the state as the process progresses, and if necessary, returns the result
 * to the caller which initiates the action, through the callback given at the creation
 * of the object. All the actual action classes inherit FeatureAction.
 *
 * <p>More than one FeatureAction objects can be up and running simultaneously,
 * maintained by {@link HdmiControlService}. Each action is passed a new command
 * arriving from the bus, and either consumes it if the command is what the action expects,
 * or yields it to other action.
 *
 * Declared as package private, accessed by {@link HdmiControlService} only.
 */
abstract class FeatureAction {

    private static final String TAG = "FeatureAction";

    // Timer handler message used for timeout event
    protected static final int MSG_TIMEOUT = 100;

    // Default timeout for the incoming command to arrive in response to a request
    protected static final int TIMEOUT_MS = 1000;

    // Default state used in common by all the feature actions.
    protected static final int STATE_NONE = 0;

    // Internal state indicating the progress of action.
    protected int mState = STATE_NONE;

    protected final HdmiControlService mService;

    // Logical address of the device for which the feature action is taken. The commands
    // generated in an action all use this field as source address.
    protected final int mSourceAddress;

    // Timer that manages timeout events.
    protected ActionTimer mActionTimer;

    FeatureAction(HdmiControlService service, int sourceAddress) {
        mService = service;
        mSourceAddress = sourceAddress;
        mActionTimer = createActionTimer(service.getServiceLooper());
    }

    @VisibleForTesting
    void setActionTimer(ActionTimer actionTimer) {
        mActionTimer = actionTimer;
    }

    /**
     * Called right after the action is created. Initialization or first step to take
     * for the action can be done in this method.
     *
     * @return true if the operation is successful; otherwise false.
     */
    abstract boolean start();

    /**
     * Process the command. Called whenever a new command arrives.
     *
     * @param cmd command to process
     * @return true if the command was consumed in the process; Otherwise false, which
     *          indicates that the command shall be handled by other actions.
     */
    abstract boolean processCommand(HdmiCecMessage cmd);

    /**
     * Called when the action should handle the timer event it created before.
     *
     * <p>CEC standard mandates each command transmission should be responded within
     * certain period of time. The method is called when the timer it created as it transmitted
     * a command gets expired. Inner logic should take an appropriate action.
     *
     * @param state the state associated with the time when the timer was created
     */
    abstract void handleTimerEvent(int state);

    /**
     * Timer handler interface used for FeatureAction classes.
     */
    interface ActionTimer {
        /**
         * Send a timer message.
         *
         * Also carries the state of the action when the timer is created. Later this state is
         * compared to the one the action is in when it receives the timer to let the action tell
         * the right timer to handle.
         *
         * @param state state of the action is in
         * @param delayMillis amount of delay for the timer
         */
        void sendTimerMessage(int state, long delayMillis);
    }

    private class ActionTimerHandler extends Handler implements ActionTimer {

        public ActionTimerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void sendTimerMessage(int state, long delayMillis) {
            sendMessageDelayed(obtainMessage(MSG_TIMEOUT, state), delayMillis);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_TIMEOUT:
                handleTimerEvent(msg.arg1);
                break;
            default:
                Slog.w(TAG, "Unsupported message:" + msg.what);
                break;
            }
        }
    }

    private ActionTimer createActionTimer(Looper looper) {
        return new ActionTimerHandler(looper);
    }

    // Add a new timer. The timer event will come to mActionTimer.handleMessage() in
    // delayMillis.
    protected void addTimer(int state, int delayMillis) {
        mActionTimer.sendTimerMessage(state, delayMillis);
    }

    static HdmiCecMessage buildCommand(int src, int dst, int opcode, byte[] params) {
        return new HdmiCecMessage(src, dst, opcode, params);
    }

    // Build a CEC command that does not have parameter.
    static HdmiCecMessage buildCommand(int src, int dst, int opcode) {
        return new HdmiCecMessage(src, dst, opcode, HdmiCecMessage.EMPTY_PARAM);
    }

    protected final void sendCommand(HdmiCecMessage cmd) {
        mService.sendCecCommand(cmd);
    }

    protected final void sendBroadcastCommand(int opcode, byte[] param) {
        sendCommand(buildCommand(mSourceAddress, HdmiCec.ADDR_BROADCAST, opcode, param));
    }

    /**
     * Finish up the action. Reset the state, and remove itself from the action queue.
     */
    protected void finish() {
        mState = STATE_NONE;
        removeAction(this);
    }

    /**
     * Remove the action from the action queue. This is called after the action finishes
     * its role.
     *
     * @param action
     */
    private void removeAction(FeatureAction action) {
        mService.removeAction(action);
    }

    // Utility methods for generating parameter byte arrays for CEC commands.
    protected static byte[] uiCommandParam(int uiCommand) {
        return new byte[] {(byte) uiCommand};
    }

    protected static byte[] physicalAddressParam(int physicalAddress) {
        return new byte[] {
                (byte) ((physicalAddress >> 8) & 0xFF),
                (byte) (physicalAddress & 0xFF)
        };
    }

    protected static byte[] pathPairParam(int oldPath, int newPath) {
        return new byte[] {
                (byte) ((oldPath >> 8) & 0xFF), (byte) (oldPath & 0xFF),
                (byte) ((newPath >> 8) & 0xFF), (byte) (newPath & 0xFF)
        };
    }
}

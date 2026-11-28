package com.elfmcys.yesstevemodel.client.input;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.util.InputUtil;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientRawInputEvent;
import net.everla.reimaginedysm.api.PlatformAPI;

public class InputStateKey {

    public static volatile boolean[] keyStates = new boolean[349];

    public static volatile boolean[] mouseStates = new boolean[8];

    private InputStateKey() {
    }

    public static void register() {
        if (PlatformAPI.isServer()) {
            return;
        }
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> {
            onKeyInput(keyCode, action);
            return EventResult.pass();
        });
        ClientRawInputEvent.MOUSE_CLICKED_PRE.register((client, button, action, mods) -> {
            onMouseInput(button, action);
            return EventResult.pass();
        });
    }

    private static void onKeyInput(int keyCode, int action) {
        if (YesSteveModel.isAvailable() && InputUtil.isPlayerReady() && 32 <= keyCode && keyCode <= 348) {
            if (action == 1) {
                keyStates[keyCode] = true;
            } else if (action == 0) {
                keyStates[keyCode] = false;
            }
        }
    }

    private static void onMouseInput(int button, int action) {
        if (YesSteveModel.isAvailable() && InputUtil.isPlayerReady() && 0 <= button && button <= 7) {
            if (action == 1) {
                mouseStates[button] = true;
            } else if (action == 0) {
                mouseStates[button] = false;
            }
        }
    }
}

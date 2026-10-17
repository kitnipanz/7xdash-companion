package com.kooo.evcam.input;

import java.util.Locale;
import java.util.UUID;

/** Which nearby BLE device is worth trying as a smart sticker. */
public final class StickerMatch {

    public static final UUID SERVICE = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb");
    public static final UUID NOTIFY = UUID.fromString("0000ffd2-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** Button channel on the 07:50:… stickers (service 1820): 31 / 32 per press type. */
    public static final UUID BUTTON = UUID.fromString("00002a60-0000-1000-8000-00805f9b34fb");

    /** Advertises the sticker service. */
    public static final int RANK_SERVICE = 0;
    /** Name looks like a sticker, service UUID not in the advert. */
    public static final int RANK_NAME = 1;
    /** Something else nearby. Shown only when no sticker candidate appears. */
    public static final int RANK_OTHER = 2;

    private StickerMatch() {
    }

    public static boolean isService(UUID uuid) {
        return uuid != null && SERVICE.equals(uuid);
    }

    public static boolean isNotify(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (NOTIFY.equals(uuid) || BUTTON.equals(uuid)) {
            return true;
        }
        return uuid.toString().toLowerCase(Locale.US).contains("ffd2");
    }

    public static boolean nameHint(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String folded = name.toLowerCase(Locale.US);
        return folded.contains("lingdong")
                || folded.contains("sticker")
                || folded.contains("csb")
                || folded.contains("smart button")
                || folded.contains("zeekr")
                || name.contains("灵动")
                || name.contains("智能贴");
    }

    public static int rank(String name, boolean advertisesService) {
        if (advertisesService) {
            return RANK_SERVICE;
        }
        if (nameHint(name)) {
            return RANK_NAME;
        }
        return RANK_OTHER;
    }
}

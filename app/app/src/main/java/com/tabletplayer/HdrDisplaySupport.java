package com.tabletplayer;

import android.app.Activity;
import android.os.Build;
import android.view.Display;

/** Isolates API 24+ HDR display calls so PlayerActivity remains safe on minSdk 19. */
final class HdrDisplaySupport {
    private HdrDisplaySupport() {}

    static boolean supports(Activity activity, int contentType) {
        if (activity == null || Build.VERSION.SDK_INT < 24) return false;
        return Api24.supports(activity, contentType);
    }

    private static final class Api24 {
        static boolean supports(Activity activity, int contentType) {
            try {
                Display display = activity.getWindowManager().getDefaultDisplay();
                Display.HdrCapabilities capabilities = display.getHdrCapabilities();
                int[] supported = capabilities == null ? null : capabilities.getSupportedHdrTypes();
                if (supported == null || supported.length == 0) return false;
                for (int type : supported) {
                    if (contentType == HdrVideoProbe.TYPE_HDR10
                            && type == Display.HdrCapabilities.HDR_TYPE_HDR10) return true;
                    if (contentType == HdrVideoProbe.TYPE_HLG
                            && type == Display.HdrCapabilities.HDR_TYPE_HLG) return true;
                    if (contentType == HdrVideoProbe.TYPE_DOLBY_VISION
                            && type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) return true;
                    if (contentType == HdrVideoProbe.TYPE_HDR_OTHER) return true;
                    if (Build.VERSION.SDK_INT >= 29 && contentType == HdrVideoProbe.TYPE_HDR10
                            && type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}

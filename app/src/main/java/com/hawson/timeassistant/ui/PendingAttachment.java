package com.hawson.timeassistant.ui;

import android.net.Uri;

final class PendingAttachment {
    final Uri uri;
    final String name, mimeType;
    final long size;

    PendingAttachment(Uri uri, String name, String mimeType, long size) {
        this.uri = uri;
        this.name = name == null || name.trim().isEmpty() ? "附件" : name.trim();
        this.mimeType = mimeType == null || mimeType.trim().isEmpty()
                ? "application/octet-stream" : mimeType.trim();
        this.size = size;
    }
    boolean isImage() { return mimeType.startsWith("image/"); }
}

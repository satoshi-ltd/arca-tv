package com.satoshilimited.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LibrarySource implements PhotoSource {
    private final PhotoLibrary library;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int index;
    private boolean first = true;

    LibrarySource(Context c, int start) {
        library = PhotoLibrary.get(c);
        index = start;
    }

    @Override public void next(int dir, ArcaHub.Callback<PhotoStream.Photo> callback) {
        io.execute(() -> {
            int target = first ? index : index + dir;
            first = false;
            PhotoStream.Photo result = null;
            if (target >= 0 && target < library.total()) {
                try {
                    library.loadFor(target);
                } catch (Exception e) {
                    AppLog.w("arca photo page: " + e.getMessage());
                }
                PhotoLibrary.Item it = library.item(target);
                if (it != null) {
                    Bitmap b = library.fetch(it, "large", it.hash + "l", false);
                    if (b != null) {
                        index = target;
                        result = new PhotoStream.Photo(b, PhotoStream.caption(it.date), it.hash, it.video);
                    }
                }
            }
            final PhotoStream.Photo shown = result;
            main.post(() -> callback.done(shown, shown == null ? ArcaHub.UNREACHABLE : null));
        });
    }

    @Override public void close() {
        io.shutdownNow();
    }
}

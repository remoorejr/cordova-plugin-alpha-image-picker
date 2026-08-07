package com.synconset;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

public class ImageFetcher {

    private int colWidth;
    private long origId;
    private ExecutorService executor;

    public ImageFetcher() {
        executor = Executors.newCachedThreadPool();
    }

    public void fetch(Integer id, ImageView imageView, int colWidth, int rotate) {
        resetPurgeTimer();
        this.colWidth = colWidth;
        this.origId = id;
        Bitmap bitmap = getBitmapFromCache(id);

        if (bitmap == null) {
            forceDownload(id, imageView, rotate);
        } else {
            cancelPotentialDownload(id, imageView);
            imageView.setImageBitmap(bitmap);
        }
    }

    private void forceDownload(Integer position, ImageView imageView, int rotate) {
        if (position == null) {
            imageView.setImageDrawable(null);
            return;
        }

        if (cancelPotentialDownload(position, imageView)) {
            BitmapFetcherTask task = new BitmapFetcherTask(imageView.getContext(), imageView, rotate);
            DownloadedDrawable downloadedDrawable = new DownloadedDrawable(task, origId);
            imageView.setImageDrawable(downloadedDrawable);
            imageView.setMinimumHeight(colWidth);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                task.executeOnExecutor(executor, position);
            } else {
                try {
                    task.execute(position);
                } catch (RejectedExecutionException ignored) {
                }
            }
        }
    }

    private static boolean cancelPotentialDownload(Integer position, ImageView imageView) {
        BitmapFetcherTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
        long origId = getOrigId(imageView);

        if (bitmapDownloaderTask != null) {
            Integer bitmapPosition = bitmapDownloaderTask.position;
            if ((bitmapPosition == null) || (!bitmapPosition.equals(position))) {
                MediaStore.Images.Thumbnails.cancelThumbnailRequest(
                        imageView.getContext().getContentResolver(),
                        origId,
                        12345
                );
                bitmapDownloaderTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapFetcherTask getBitmapDownloaderTask(ImageView imageView) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof DownloadedDrawable) {
                return ((DownloadedDrawable) drawable).getBitmapDownloaderTask();
            }
        }
        return null;
    }

    private static long getOrigId(ImageView imageView) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof DownloadedDrawable) {
                return ((DownloadedDrawable) drawable).getOrigId();
            }
        }
        return -1;
    }

    class BitmapFetcherTask extends AsyncTask<Integer, Void, Bitmap> {
        private Integer position;
        private final WeakReference<ImageView> imageViewReference;
        private final Context mContext;
        private final int rotate;

        public BitmapFetcherTask(Context context, ImageView imageView, int rotate) {
            imageViewReference = new WeakReference<>(imageView);
            mContext = context;
            this.rotate = rotate;
        }

        @Override
        protected Bitmap doInBackground(Integer... params) {
            try {
                position = params[0];
                if (isCancelled()) return null;

                Bitmap thumb = MediaStore.Images.Thumbnails.getThumbnail(
                        mContext.getContentResolver(),
                        position,
                        12345,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                );

                if (isCancelled() || thumb == null) return null;

                if (rotate != 0) {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(rotate); // FIX: apply rotation
                    thumb = Bitmap.createBitmap(
                            thumb, 0, 0, thumb.getWidth(), thumb.getHeight(), matrix, true
                    );
                }

                return thumb;
            } catch (OutOfMemoryError error) {
                clearCache();
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (isCancelled()) bitmap = null;
            addBitmapToCache(position, bitmap);

            ImageView imageView = imageViewReference.get();
            if (imageView == null) return;

            BitmapFetcherTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
            if (this == bitmapDownloaderTask) {
                imageView.setImageBitmap(bitmap);
                Animation anim = AnimationUtils.loadAnimation(imageView.getContext(), android.R.anim.fade_in);
                imageView.setAnimation(anim);
                anim.start();
            }
        }
    }

    static class DownloadedDrawable extends ColorDrawable {
        private final WeakReference<BitmapFetcherTask> bitmapDownloaderTaskReference;
        private final long origId;

        public DownloadedDrawable(BitmapFetcherTask bitmapDownloaderTask, long origId) {
            super(Color.TRANSPARENT);
            bitmapDownloaderTaskReference = new WeakReference<>(bitmapDownloaderTask);
            this.origId = origId;
        }

        public long getOrigId() {
            return origId;
        }

        public BitmapFetcherTask getBitmapDownloaderTask() {
            return bitmapDownloaderTaskReference.get();
        }
    }

    private static final int HARD_CACHE_CAPACITY = 100;
    private final HashMap<Integer, Bitmap> sHardBitmapCache = new LinkedHashMap<Integer, Bitmap>(
            HARD_CACHE_CAPACITY / 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(HashMap.Entry<Integer, Bitmap> eldest) {
            if (size() > HARD_CACHE_CAPACITY) {
                sSoftBitmapCache.put(eldest.getKey(), new SoftReference<>(eldest.getValue()));
                return true;
            }
            return false;
        }
    };

    private static final ConcurrentHashMap<Integer, SoftReference<Bitmap>> sSoftBitmapCache =
            new ConcurrentHashMap<>(HARD_CACHE_CAPACITY / 2);

    private final Handler purgeHandler = new Handler();

    private void addBitmapToCache(Integer position, Bitmap bitmap) {
        if (bitmap != null) {
            synchronized (sHardBitmapCache) {
                sHardBitmapCache.put(position, bitmap);
            }
        }
    }

    private Bitmap getBitmapFromCache(Integer position) {
        synchronized (sHardBitmapCache) {
            Bitmap bitmap = sHardBitmapCache.get(position);
            if (bitmap != null) return bitmap;
        }

        SoftReference<Bitmap> bitmapReference = sSoftBitmapCache.get(position);
        if (bitmapReference != null) {
            Bitmap bitmap = bitmapReference.get();
            if (bitmap != null) return bitmap;
            sSoftBitmapCache.remove(position);
        }

        return null;
    }

    public void clearCache() {
        sHardBitmapCache.clear();
        sSoftBitmapCache.clear();
    }

    private void resetPurgeTimer() {
        // no-op (legacy behavior preserved)
        purgeHandler.removeCallbacksAndMessages(null);
    }
}
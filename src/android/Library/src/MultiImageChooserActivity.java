/*
 * Copyright (c) 2012, David Erosa
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following  conditions are met:
 *
 *   Redistributions of source code must retain the above copyright notice, 
 *      this list of conditions and the following disclaimer.
 *   Redistributions in binary form must reproduce the above copyright notice, 
 *      this list of conditions and the following  disclaimer in the 
 *      documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,  BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT  SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR  BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDIN G NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH  DAMAGE
 *
 * Code modified by Andrew Stephan for Sync OnSet
 *
 */

package com.synconset;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import android.app.AlertDialog;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Base64;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

public class MultiImageChooserActivity extends AppCompatActivity implements
        AdapterView.OnItemClickListener,
        LoaderManager.LoaderCallbacks<Cursor> {

    public static final int NOLIMIT = -1;
    public static final String MAX_IMAGES_KEY = "MAX_IMAGES";
    public static final String WIDTH_KEY = "WIDTH";
    public static final String HEIGHT_KEY = "HEIGHT";
    public static final String QUALITY_KEY = "QUALITY";
    public static final String OUTPUT_TYPE_KEY = "OUTPUT_TYPE";

    private static final int CURSORLOADER_THUMBS = 0;
    private static final int CURSORLOADER_REAL = 1;

    private ImageAdapter ia;
    private Cursor imagecursor, actualimagecursor;
    private int image_column_index, image_column_orientation, actual_image_id_column_index, orientation_column_index;
    private int colWidth;

    private final Map<Long, Integer> selectedItems = new HashMap<>();
    private final SparseBooleanArray checkStatus = new SparseBooleanArray();

    private int maxImages;
    private int maxImageCount;
    private int desiredWidth;
    private int desiredHeight;
    private int quality;
    private OutputType outputType;

    private final ImageFetcher fetcher = new ImageFetcher();
    private int selectedColor = 0xff32b2e1;
    private boolean shouldRequestThumb = true;

    private FakeR fakeR;
    private View abDoneView;
    private View abDiscardView;
    private ProgressDialog progress;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fakeR = new FakeR(this);
        setContentView(fakeR.getId("layout", "multiselectorgrid"));

        maxImages = getIntent().getIntExtra(MAX_IMAGES_KEY, NOLIMIT);
        desiredWidth = getIntent().getIntExtra(WIDTH_KEY, 0);
        desiredHeight = getIntent().getIntExtra(HEIGHT_KEY, 0);
        quality = getIntent().getIntExtra(QUALITY_KEY, 100);
        maxImageCount = maxImages;
        outputType = OutputType.fromValue(getIntent().getIntExtra(OUTPUT_TYPE_KEY, 0));

        Display display = getWindowManager().getDefaultDisplay();
        int width = display.getWidth();
        colWidth = width / 4;

        GridView gridView = findViewById(fakeR.getId("id", "gridview"));
        gridView.setOnItemClickListener(this);
        gridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            private int lastFirstItem = 0;
            private long timestamp = System.currentTimeMillis();

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) {
                    shouldRequestThumb = true;
                    ia.notifyDataSetChanged();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                float dt = System.currentTimeMillis() - timestamp;
                if (firstVisibleItem != lastFirstItem && dt > 0) {
                    double speed = 1 / dt * 1000;
                    lastFirstItem = firstVisibleItem;
                    timestamp = System.currentTimeMillis();
                    shouldRequestThumb = speed < visibleItemCount;
                }
            }
        });

        ia = new ImageAdapter();
        gridView.setAdapter(ia);

        LoaderManager.enableDebugLogging(false);
        getLoaderManager().initLoader(CURSORLOADER_THUMBS, null, this);
        getLoaderManager().initLoader(CURSORLOADER_REAL, null, this);

        setupHeader();
        updateAcceptButton();

        progress = new ProgressDialog(this);
        progress.setTitle(getString(fakeR.getId("string", "multi_image_picker_processing_images_title")));
        progress.setMessage(getString(fakeR.getId("string", "multi_image_picker_processing_images_message")));
    }

    @Override
    public void onItemClick(AdapterView<?> arg0, View view, int position, long id) {
        Long mediaId = getImageId(position);
        int rotation = getImageRotation(position);

        if (mediaId == null) return;

        boolean isChecked = !isChecked(position);

        if (maxImages == 0 && isChecked) {
            isChecked = false;
            new AlertDialog.Builder(this)
                    .setTitle(String.format(getString(fakeR.getId("string", "max_count_photos_title")), maxImageCount))
                    .setMessage(String.format(getString(fakeR.getId("string", "max_count_photos_message")), maxImageCount))
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    })
                    .create()
                    .show();

        } else if (isChecked) {
            selectedItems.put(mediaId, rotation);

            if (maxImageCount == 1) {
                selectClicked();
            } else {
                maxImages--;
                ImageView imageView = (ImageView) view;
                if (android.os.Build.VERSION.SDK_INT >= 16) imageView.setImageAlpha(128);
                else imageView.setAlpha(128f);
                view.setBackgroundColor(selectedColor);
            }
        } else {
            selectedItems.remove(mediaId);
            maxImages++;
            ImageView imageView = (ImageView) view;
            if (android.os.Build.VERSION.SDK_INT >= 16) imageView.setImageAlpha(255);
            else imageView.setAlpha(255f);
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        checkStatus.put(position, isChecked);
        updateAcceptButton();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int cursorID, Bundle arg1) {
        ArrayList<String> cols = new ArrayList<>();
        cols.add(MediaStore.Images.Media._ID);
        cols.add(MediaStore.Images.Media.ORIENTATION);

        return new CursorLoader(
                this,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols.toArray(new String[0]),
                null,
                null,
                "DATE_MODIFIED DESC"
        );
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if (cursor == null) return;

        switch (loader.getId()) {
            case CURSORLOADER_THUMBS:
                imagecursor = cursor;
                image_column_index = imagecursor.getColumnIndex(MediaStore.Images.Media._ID);
                image_column_orientation = imagecursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
                ia.notifyDataSetChanged();
                break;

            case CURSORLOADER_REAL:
                actualimagecursor = cursor;
                actual_image_id_column_index = actualimagecursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                orientation_column_index = actualimagecursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION);
                break;
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        if (loader.getId() == CURSORLOADER_THUMBS) imagecursor = null;
        if (loader.getId() == CURSORLOADER_REAL) actualimagecursor = null;
    }

    public void cancelClicked() {
        setResult(RESULT_CANCELED);
        finish();
    }

    public void selectClicked() {
        abDiscardView.setEnabled(false);
        abDoneView.setEnabled(false);
        progress.show();

        if (selectedItems.isEmpty()) {
            setResult(RESULT_CANCELED);
            progress.dismiss();
            finish();
        } else {
            setRequestedOrientation(getResources().getConfiguration().orientation);
            new ResizeImagesTask().execute(selectedItems.entrySet());
        }
    }

    private void updateAcceptButton() {
        if (abDoneView != null) {
            abDoneView.setEnabled(!selectedItems.isEmpty());
        }
    }

    private void setupHeader() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View customActionBarView = inflater.inflate(
                fakeR.getId("layout", "actionbar_custom_view_done_discard"),
                null
        );

        abDoneView = customActionBarView.findViewById(fakeR.getId("id", "actionbar_done"));
        abDoneView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectClicked(); }
        });

        abDiscardView = customActionBarView.findViewById(fakeR.getId("id", "actionbar_discard"));
        abDiscardView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelClicked(); }
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM, ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE);
            actionBar.setCustomView(customActionBarView, new ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private Long getImageId(int position) {
        if (actualimagecursor == null) return null;
        if (!actualimagecursor.moveToPosition(position)) return null;
        try {
            return actualimagecursor.getLong(actual_image_id_column_index);
        } catch (Exception e) {
            return null;
        }
    }

    private int getImageRotation(int position) {
        if (actualimagecursor == null) return 0;
        if (!actualimagecursor.moveToPosition(position)) return 0;
        try {
            return actualimagecursor.getInt(orientation_column_index);
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isChecked(int position) {
        return checkStatus.get(position);
    }

    private class SquareImageView extends ImageView {
        public SquareImageView(Context context) { super(context); }
        @Override public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }

    private class ImageAdapter extends BaseAdapter {

        public int getCount() {
            return imagecursor != null ? imagecursor.getCount() : 0;
        }

        public Object getItem(int position) { return position; }

        public long getItemId(int position) { return position; }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                ImageView temp = new SquareImageView(MultiImageChooserActivity.this);
                temp.setScaleType(ImageView.ScaleType.CENTER_CROP);
                convertView = temp;
            }

            ImageView imageView = (ImageView) convertView;
            imageView.setImageBitmap(null);

            if (imagecursor == null || !imagecursor.moveToPosition(position) || image_column_index == -1) {
                return imageView;
            }

            final int id = imagecursor.getInt(image_column_index);
            final int rotate = imagecursor.getInt(image_column_orientation);

            if (isChecked(position)) {
                if (android.os.Build.VERSION.SDK_INT >= 16) imageView.setImageAlpha(128);
                else imageView.setAlpha(128f);
                imageView.setBackgroundColor(selectedColor);
            } else {
                if (android.os.Build.VERSION.SDK_INT >= 16) imageView.setImageAlpha(255);
                else imageView.setAlpha(255f);
                imageView.setBackgroundColor(Color.TRANSPARENT);
            }

            if (shouldRequestThumb) {
                fetcher.fetch(id, imageView, colWidth, rotate);
            }

            return imageView;
        }
    }

    private class ResizeImagesTask extends AsyncTask<Set<Entry<Long, Integer>>, Void, ArrayList<String>> {
        private Exception asyncTaskError = null;

        @Override
        protected ArrayList<String> doInBackground(Set<Entry<Long, Integer>>... itemSets) {
            Set<Entry<Long, Integer>> items = itemSets[0];
            ArrayList<String> results = new ArrayList<>();

            try {
                Iterator<Entry<Long, Integer>> i = items.iterator();
                while (i.hasNext()) {
                    Entry<Long, Integer> item = i.next();
                    long mediaId = item.getKey();
                    int rotate = item.getValue();

                    Uri imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    decodeUri(imageUri, bounds);

                    int width = bounds.outWidth;
                    int height = bounds.outHeight;
                    float scale = calculateScale(width, height);

                    BitmapFactory.Options opts = null;
                    if (scale < 1) {
                        int finalWidth = (int) (width * scale);
                        int finalHeight = (int) (height * scale);
                        int inSampleSize = calculateInSampleSize(bounds, finalWidth, finalHeight);
                        opts = new BitmapFactory.Options();
                        opts.inSampleSize = inSampleSize;
                    }

                    Bitmap bmp = tryToGetBitmap(imageUri, opts, rotate, scale < 1);

                    if (outputType == OutputType.FILE_URI) {
                        File file = storeImage(bmp, "picked_" + mediaId + ".jpg");
                        results.add(Uri.fromFile(file).toString());
                    } else {
                        results.add(getBase64OfImage(bmp));
                    }
                }

                return results;
            } catch (IOException e) {
                asyncTaskError = e;
                return new ArrayList<>();
            }
        }

        @Override
        protected void onPostExecute(ArrayList<String> al) {
            Intent data = new Intent();

            if (asyncTaskError != null) {
                Bundle res = new Bundle();
                res.putString("ERRORMESSAGE", asyncTaskError.getMessage());
                data.putExtras(res);
                setResult(RESULT_CANCELED, data);

            } else if (!al.isEmpty()) {
                Bundle res = new Bundle();
                res.putStringArrayList("MULTIPLEFILENAMES", al);
                if (imagecursor != null) res.putInt("TOTALFILES", imagecursor.getCount());

                int sync = ResultIPC.get().setLargeData(res);
                data.putExtra("bigdata:synccode", sync);
                setResult(RESULT_OK, data);

            } else {
                setResult(RESULT_CANCELED, data);
            }

            progress.dismiss();
            finish();
        }

        private Bitmap tryToGetBitmap(Uri uri, BitmapFactory.Options options, int rotate, boolean shouldScale)
                throws IOException {

            Bitmap bmp = decodeUri(uri, options);
            if (bmp == null) throw new IOException("The image file could not be opened.");

            if (options != null && shouldScale) {
                float scale = calculateScale(bmp.getWidth(), bmp.getHeight());
                bmp = getResizedBitmap(bmp, scale);
            }

            if (rotate != 0) {
                Matrix matrix = new Matrix();
                matrix.setRotate(rotate);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            }

            return bmp;
        }

        private Bitmap decodeUri(Uri uri, BitmapFactory.Options options) throws IOException {
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, options);
            } finally {
                if (in != null) in.close();
            }
        }

        private File storeImage(Bitmap bmp, String fileName) throws IOException {
            int idx = fileName.lastIndexOf('.');
            String name = idx >= 0 ? fileName.substring(0, idx) : fileName;
            String ext = idx >= 0 ? fileName.substring(idx) : ".jpg";

            File file = File.createTempFile("tmp_" + name, ext);
            OutputStream outStream = new FileOutputStream(file);

            if (".png".equalsIgnoreCase(ext)) bmp.compress(Bitmap.CompressFormat.PNG, quality, outStream);
            else bmp.compress(Bitmap.CompressFormat.JPEG, quality, outStream);

            outStream.flush();
            outStream.close();
            return file;
        }

        private Bitmap getResizedBitmap(Bitmap bm, float factor) {
            int width = bm.getWidth();
            int height = bm.getHeight();
            Matrix matrix = new Matrix();
            matrix.postScale(factor, factor);
            return Bitmap.createBitmap(bm, 0, 0, width, height, matrix, false);
        }

        private String getBase64OfImage(Bitmap bm) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private float calculateScale(int width, int height) {
        float widthScale = 1.0f;
        float heightScale = 1.0f;
        float scale = 1.0f;

        if (desiredWidth > 0 || desiredHeight > 0) {
            if (desiredHeight == 0 && desiredWidth < width) {
                scale = (float) desiredWidth / width;
            } else if (desiredWidth == 0 && desiredHeight < height) {
                scale = (float) desiredHeight / height;
            } else {
                if (desiredWidth > 0 && desiredWidth < width) widthScale = (float) desiredWidth / width;
                if (desiredHeight > 0 && desiredHeight < height) heightScale = (float) desiredHeight / height;
                scale = Math.min(widthScale, heightScale);
            }
        }

        return scale;
    }

    enum OutputType {
        FILE_URI(0), BASE64_STRING(1);
        int value;
        OutputType(int value) { this.value = value; }
        public static OutputType fromValue(int value) {
            for (OutputType type : values()) {
                if (type.value == value) return type;
            }
            throw new IllegalArgumentException("Invalid enum value specified");
        }
    }
}
/**
 * An Image Picker Plugin for Cordova/PhoneGap.
 */
package com.synconset;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class ImagePicker extends CordovaPlugin {

    private static final String TAG = "ImagePicker";

    private static final String ACTION_GET_PICTURES = "getPictures";
    private static final String ACTION_HAS_READ_PERMISSION = "hasReadPermission";
    private static final String ACTION_REQUEST_READ_PERMISSION = "requestReadPermission";

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int PICKER_REQUEST_CODE = 1001;

    private CallbackContext callbackContext;
    private Intent pendingImagePickerIntent;
    private boolean launchPickerAfterPermissionGrant = false;

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        if (ACTION_HAS_READ_PERMISSION.equals(action)) {
            boolean granted = hasReadPermission();
            Log.d(TAG, "hasReadPermission => " + granted + " (sdk=" + Build.VERSION.SDK_INT + ", perm=" + getReadPermission() + ")");
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, granted));
            return true;
        }

        if (ACTION_REQUEST_READ_PERMISSION.equals(action)) {
            Log.d(TAG, "requestReadPermission invoked from JS");
            requestReadPermission(false);
            return true;
        }

        if (ACTION_GET_PICTURES.equals(action)) {
            final JSONObject params = args.getJSONObject(0);
            final Intent imagePickerIntent = new Intent(cordova.getActivity(), MultiImageChooserActivity.class);

            int max = params.has("maximumImagesCount") ? params.getInt("maximumImagesCount") : 20;
            int desiredWidth = params.has("width") ? params.getInt("width") : 0;
            int desiredHeight = params.has("height") ? params.getInt("height") : 0;
            int quality = params.has("quality") ? params.getInt("quality") : 100;
            int outputType = params.has("outputType") ? params.getInt("outputType") : 0;

            imagePickerIntent.putExtra("MAX_IMAGES", max);
            imagePickerIntent.putExtra("WIDTH", desiredWidth);
            imagePickerIntent.putExtra("HEIGHT", desiredHeight);
            imagePickerIntent.putExtra("QUALITY", quality);
            imagePickerIntent.putExtra("OUTPUT_TYPE", outputType);

            if (hasReadPermission()) {
                Log.d(TAG, "Permission already granted. Launching picker.");
                cordova.startActivityForResult(this, imagePickerIntent, PICKER_REQUEST_CODE);
            } else {
                Log.d(TAG, "Permission missing. Requesting before launch.");
                pendingImagePickerIntent = imagePickerIntent;
                launchPickerAfterPermissionGrant = true;
                requestReadPermission(true);
            }
            return true;
        }

        return false;
    }

    private String getReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return PackageManager.PERMISSION_GRANTED ==
                ContextCompat.checkSelfPermission(cordova.getActivity(), getReadPermission());
    }

    private void requestReadPermission(boolean launchAfterGrant) {
        String permission = getReadPermission();

        if (!hasReadPermission()) {
            launchPickerAfterPermissionGrant = launchAfterGrant;
            Log.d(TAG, "Requesting permission: " + permission + ", launchAfterGrant=" + launchAfterGrant);

            // Use Cordova permission pipeline so onRequestPermissionResult is called on plugin.
            cordova.requestPermission(this, PERMISSION_REQUEST_CODE, permission);
        } else {
            Log.d(TAG, "Permission already granted in requestReadPermission(). launchAfterGrant=" + launchAfterGrant);

            if (launchAfterGrant && pendingImagePickerIntent != null) {
                cordova.startActivityForResult(this, pendingImagePickerIntent, PICKER_REQUEST_CODE);
                pendingImagePickerIntent = null;
                launchPickerAfterPermissionGrant = false;
            } else if (callbackContext != null) {
                callbackContext.success();
            }
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        String permission = (permissions != null && permissions.length > 0) ? permissions[0] : getReadPermission();
        boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        Log.d(TAG, "onRequestPermissionResult permission=" + permission + ", granted=" + granted);

        if (granted) {
            if (launchPickerAfterPermissionGrant && pendingImagePickerIntent != null) {
                Log.d(TAG, "Permission granted; launching pending picker intent.");
                cordova.startActivityForResult(this, pendingImagePickerIntent, PICKER_REQUEST_CODE);
                pendingImagePickerIntent = null;
                launchPickerAfterPermissionGrant = false;
            } else if (callbackContext != null) {
                Log.d(TAG, "Permission granted; returning success to callback.");
                callbackContext.success();
            }
            return;
        }

        boolean neverAskAgain = false;
        Activity activity = cordova != null ? cordova.getActivity() : null;
        if (activity != null) {
            neverAskAgain = !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
        }

        String message;
        if (neverAskAgain) {
            message = "Permission denied permanently. Enable Photos/Media permission in Android Settings.";
        } else {
            message = "Permission denied";
        }

        Log.w(TAG, "Permission denied. neverAskAgain=" + neverAskAgain + ", permission=" + permission);

        // Clear pending picker state on denial.
        pendingImagePickerIntent = null;
        launchPickerAfterPermissionGrant = false;

        if (callbackContext != null) {
            callbackContext.error(message);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICKER_REQUEST_CODE) {
            Log.d(TAG, "Ignoring onActivityResult requestCode=" + requestCode);
            return;
        }

        if (callbackContext == null) {
            Log.e(TAG, "callbackContext is null in onActivityResult");
            return;
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            int sync = data.getIntExtra("bigdata:synccode", -1);
            Bundle bigData = ResultIPC.get().getLargeData(sync);

            if (bigData == null) {
                Log.e(TAG, "ResultIPC returned null data for sync code " + sync);
                callbackContext.error("No image payload returned");
                return;
            }

            ArrayList<String> fileNames = bigData.getStringArrayList("MULTIPLEFILENAMES");
            if (fileNames == null) fileNames = new ArrayList<>();

            Log.d(TAG, "Picker success. Returned items=" + fileNames.size());
            callbackContext.success(new JSONArray(fileNames));

        } else if (resultCode == Activity.RESULT_CANCELED && data != null) {
            String error = data.getStringExtra("ERRORMESSAGE");
            Log.d(TAG, "Picker canceled with error message: " + error);
            if (error != null && !error.isEmpty()) {
                callbackContext.error(error);
            } else {
                callbackContext.success(new JSONArray()); // treat user cancel as empty selection
            }

        } else if (resultCode == Activity.RESULT_CANCELED) {
            Log.d(TAG, "Picker canceled by user (no data)");
            callbackContext.success(new JSONArray());

        } else {
            Log.e(TAG, "Unexpected picker resultCode=" + resultCode);
            callbackContext.error("No images selected");
        }
    }

    @Override
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        Log.d(TAG, "Restoring callback context after Activity recreation.");
        this.callbackContext = callbackContext;
    }
}
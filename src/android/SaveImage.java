package cordova.plugin.saveimage;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SaveImage extends CordovaPlugin {

    private static final String READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final String WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final String READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final int REQUEST_AUTHORIZATION_REQ_CODE = 0;
    private static final String ACTION_REQUEST_AUTHORIZATION = "requestAuthorization";
    private static final String ACTION_SAVE_IMAGE = "saveImage";
    private ImageService service;
    private CallbackContext callbackContext;

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
        this.service = ImageService.getInstance();
        if (ACTION_REQUEST_AUTHORIZATION.equals(action)) {
            try {
                final JSONObject options = args.optJSONObject(0);
                final boolean read = options.getBoolean("read");
                final boolean write = options.getBoolean("write");
                requestAppropriatePermissions(read, write);
            } catch (Exception e) {
                e.printStackTrace();
                callbackContext.error(e.getMessage());
            }
            return true;
        } else if (ACTION_SAVE_IMAGE.equals(action)) {
            try {
                final String fileName = args.getString(0);
                final String url = args.getString(1);
                final String album = args.getString(2);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ uses scoped storage
                    service.saveImage(getContext(), cordova, fileName, url, album, callbackContext::success);
                } else if (!cordova.hasPermission(WRITE_EXTERNAL_STORAGE)) {
                    callbackContext.error(ImageService.PERMISSION_ERROR);
                    return false;
                } else {
                    service.saveImage(getContext(), cordova, fileName, url, album, callbackContext::success);
                }
            } catch (Exception e) {
                e.printStackTrace();
                callbackContext.error(e.getMessage());
            }
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        super.onRequestPermissionResult(requestCode, permissions, grantResults);
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                this.callbackContext.error(ImageService.PERMISSION_ERROR);
                return;
            }
        }
        this.callbackContext.success();
    }

    private void requestAppropriatePermissions(boolean read, boolean write) {
        List<String> permissions = new ArrayList<String>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses more granular permissions
            if (read) {
                permissions.add(READ_MEDIA_IMAGES);
            }
            // Write is handled through MediaStore API
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12
            if (read) {
                permissions.add(READ_EXTERNAL_STORAGE);
            }
            // Write is handled through MediaStore API
        } else {
            // Android 9 and below
            if (read) {
                permissions.add(READ_EXTERNAL_STORAGE);
            }
            if (write) {
                permissions.add(WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissions.isEmpty()) {
            cordova.requestPermissions(this, REQUEST_AUTHORIZATION_REQ_CODE,
                    permissions.toArray(new String[0]));
        } else {
            this.callbackContext.success();
        }
    }

    private Context getContext() {
        return this.cordova.getActivity().getApplicationContext();
    }
}
package cordova.plugin.saveimage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;

import org.apache.cordova.CordovaInterface;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageService {
    private static ImageService instance = null;
    private SimpleDateFormat dateFormatter;
    private Pattern dataURLPattern = Pattern.compile("^data:(.+?)/(.+?);base64,");

    protected ImageService() {
        dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        dateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static final String PERMISSION_ERROR = "Permission Denial: This application is not allowed to access Photo data.";

    public static ImageService getInstance() {
        if (instance == null) {
            synchronized (ImageService.class) {
                if (instance == null) {
                    instance = new ImageService();
                }
            }
        }
        return instance;
    }

    public void saveImage(final Context context, final CordovaInterface cordova, final String fileName, final String url, String album, final JSONObjectRunnable completion) throws IOException, URISyntaxException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore API for Android 10+
            saveMediaUsingMediaStore(context, cordova, fileName, url, album, imageMimeToExtension, filePath -> {
                try {
                    String whereClause = MediaStore.MediaColumns.DATA + " = \"" + filePath + "\"";
                    queryLibrary(context, whereClause, (chunk, chunkNum, isLastChunk) -> completion.run(chunk.size() == 1 ? chunk.get(0) : new JSONObject()));
                } catch (Exception e) {
                    completion.run(new JSONObject());
                }
            });
        } else {
            // Legacy method for older Android versions
            saveMedia(context, cordova, fileName, url, album, imageMimeToExtension, filePath -> {
                try {
                    String whereClause = MediaStore.MediaColumns.DATA + " = \"" + filePath + "\"";
                    queryLibrary(context, whereClause, (chunk, chunkNum, isLastChunk) -> completion.run(chunk.size() == 1 ? chunk.get(0) : new JSONObject()));
                } catch (Exception e) {
                    completion.run(new JSONObject());
                }
            });
        }
    }

    private void saveMediaUsingMediaStore(Context context, CordovaInterface cordova, String fileName, String url, String album, Map<String, String> mimeToExtension, FilePathRunnable completion) throws IOException, URISyntaxException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues contentValues = new ContentValues();

        String mime = "image/jpeg"; // Default
        String extension = ".jpg";  // Default
        byte[] imageData = null;

        // Process URL or data URL
        if (url.startsWith("data:")) {
            Matcher matcher = dataURLPattern.matcher(url);
            if (!matcher.find()) {
                throw new IllegalArgumentException("The dataURL is in incorrect format");
            }
            mime = matcher.group(1) + "/" + matcher.group(2);
            int dataPos = matcher.end();
            String base64 = url.substring(dataPos);
            imageData = Base64.decode(base64, Base64.DEFAULT);

            String mimeExtension = mimeToExtension.get(matcher.group(2));
            if (mimeExtension != null) {
                extension = mimeExtension;
            } else {
                extension = "." + matcher.group(2);
            }
        } else {
            extension = url.contains(".") ? url.substring(url.lastIndexOf(".")) : "";
            if (extension.contains("jpg") || extension.contains("JPG")) {
                extension = ".jpg";
                mime = "image/jpeg";
            } else if (extension.contains("png") || extension.contains("PNG")) {
                extension = ".png";
                mime = "image/png";
            }
        }

        // Setup content values for MediaStore
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName + extension);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mime);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + album);
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        // Insert the image into MediaStore
        Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        if (imageUri == null) {
            throw new IOException("Failed to create new MediaStore record");
        }

        try (OutputStream os = resolver.openOutputStream(imageUri)) {
            if (os == null) {
                throw new IOException("Failed to open output stream");
            }

            if (imageData != null) {
                // Write base64 decoded data
                os.write(imageData);
            } else {
                // Write from URL
                InputStream is;
                if (url.startsWith("file:///android_asset/")) {
                    String assetUrl = url.replace("file:///android_asset/", "");
                    is = cordova.getActivity().getApplicationContext().getAssets().open(assetUrl);
                } else {
                    is = new URL(url).openStream();
                }
                copyStream(is, os);
                is.close();
            }

            os.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear();
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(imageUri, contentValues, null, null);
        }

        // Get the file path for the completion callback
        String[] projection = {MediaStore.Images.Media.DATA};
        Cursor cursor = resolver.query(imageUri, projection, null, null, null);
        String filePath = null;
        if (cursor != null && cursor.moveToFirst()) {
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            filePath = cursor.getString(columnIndex);
            cursor.close();
        }

        if (filePath != null) {
            completion.run(filePath);
        } else {
            // If path couldn't be determined, use URI string as fallback
            completion.run(imageUri.toString());
        }
    }

    private ArrayList<JSONObject> queryContentProvider(Context context, Uri collection, JSONObject columns, String whereClause) throws JSONException {
        final ArrayList<String> columnNames = new ArrayList<String>();
        final ArrayList<String> columnValues = new ArrayList<String>();
        Iterator<String> iteratorFields = columns.keys();
        while (iteratorFields.hasNext()) {
            String column = iteratorFields.next();
            columnNames.add(column);
            columnValues.add("" + columns.getString(column));
        }
        final String sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC";
        final Cursor cursor = context.getContentResolver().query(collection, columnValues.toArray(new String[columns.length()]), whereClause, null, sortOrder);
        final ArrayList<JSONObject> buffer = new ArrayList<JSONObject>();
        if (cursor.moveToFirst()) {
            do {
                JSONObject item = new JSONObject();
                for (String column : columnNames) {
                    int columnIndex = cursor.getColumnIndex(columns.get(column).toString());
                    if (column.startsWith("int.")) {
                        item.put(column.substring(4), cursor.getInt(columnIndex));
                        if (column.substring(4).equals("width") && item.getInt("width") == 0) {
                            System.err.println("cursor: " + cursor.getInt(columnIndex));
                        }
                    } else if (column.startsWith("float.")) {
                        item.put(column.substring(6), cursor.getFloat(columnIndex));
                    } else if (column.startsWith("date.")) {
                        long intDate = cursor.getLong(columnIndex);
                        Date date = new Date(intDate);
                        item.put(column.substring(5), dateFormatter.format(date));
                    } else {
                        item.put(column, cursor.getString(columnIndex));
                    }
                }
                buffer.add(item);
                // TODO: return partial result
            }
            while (cursor.moveToNext());
        }
        cursor.close();
        return buffer;
    }

    private void queryLibrary(Context context, String whereClause, ChunkResultRunnable completion) throws JSONException {
        queryLibrary(context, 0, 0, false, whereClause, completion);
    }

    private void queryLibrary(Context context, int itemsInChunk, double chunkTimeSec, boolean includeAlbumData, String whereClause, ChunkResultRunnable completion)
            throws JSONException {
        JSONObject columns = new JSONObject() {{
            put("int.id", MediaStore.Images.Media._ID);
            put("fileName", MediaStore.Images.ImageColumns.DISPLAY_NAME);
            put("int.width", MediaStore.Images.ImageColumns.WIDTH);
            put("int.height", MediaStore.Images.ImageColumns.HEIGHT);
            put("albumId", MediaStore.Images.ImageColumns.BUCKET_ID);
            put("date.creationDate", MediaStore.Images.ImageColumns.DATE_TAKEN);
            put("float.latitude", MediaStore.Images.ImageColumns.LATITUDE);
            put("float.longitude", MediaStore.Images.ImageColumns.LONGITUDE);
            put("nativeURL", MediaStore.MediaColumns.DATA); // will not be returned to javascript
        }};
        final ArrayList<JSONObject> queryResults = queryContentProvider(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, whereClause);
        ArrayList<JSONObject> chunk = new ArrayList<JSONObject>();
        long chunkStartTime = SystemClock.elapsedRealtime();
        int chunkNum = 0;
        for (int i = 0; i < queryResults.size(); i++) {
            JSONObject queryResult = queryResults.get(i);
            // swap width and height if needed
            try {
                if (queryResult.has("nativeURL") && queryResult.getString("nativeURL") != null) {
                    int orientation = getImageOrientation(new File(queryResult.getString("nativeURL")));
                    if (isOrientationSwapsDimensions(orientation)) { // swap width and height
                        int tempWidth = queryResult.getInt("width");
                        queryResult.put("width", queryResult.getInt("height"));
                        queryResult.put("height", tempWidth);
                    }
                }
            } catch (IOException e) {
                // Do nothing
            }
            // photoId is in format "imageid;imageurl"
            queryResult.put("id", queryResult.get("id") + ";" + queryResult.get("nativeURL"));
            queryResult.remove("nativeURL"); // Not needed
            String albumId = queryResult.getString("albumId");
            queryResult.remove("albumId");
            if (includeAlbumData) {
                JSONArray albumsArray = new JSONArray();
                albumsArray.put(albumId);
                queryResult.put("albumIds", albumsArray);
            }
            chunk.add(queryResult);
            if (i == queryResults.size() - 1) { // Last item
                completion.run(chunk, chunkNum, true);
            } else if ((itemsInChunk > 0 && chunk.size() == itemsInChunk) || (chunkTimeSec > 0 && (SystemClock.elapsedRealtime() - chunkStartTime) >= chunkTimeSec * 1000)) {
                completion.run(chunk, chunkNum, false);
                chunkNum += 1;
                chunk = new ArrayList<JSONObject>();
                chunkStartTime = SystemClock.elapsedRealtime();
            }
        }
    }

    private static void copyStream(InputStream source, OutputStream target) throws IOException {
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];
        int len;
        while ((len = source.read(buffer)) != -1) {
            target.write(buffer, 0, len);
        }
    }

    private static int getImageOrientation(File imageFile) throws IOException {
        if (!imageFile.exists()) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
        ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        return orientation;
    }

    // Returns true if orientation rotates image by 90 or 270 degrees.
    private static boolean isOrientationSwapsDimensions(int orientation) {
        return orientation == ExifInterface.ORIENTATION_TRANSPOSE // 5
                || orientation == ExifInterface.ORIENTATION_ROTATE_90 // 6
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE // 7
                || orientation == ExifInterface.ORIENTATION_ROTATE_270; // 8
    }

    private static File makeAlbumInPhotoLibrary(String album) {
        File albumDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album);
        if (!albumDirectory.exists()) {
            albumDirectory.mkdirs();
        }
        return albumDirectory;
    }

    private void addFileToMediaLibrary(Context context, File file, final FilePathRunnable completion) {
        String filePath = file.getAbsolutePath();
        MediaScannerConnection.scanFile(context, new String[]{filePath}, null, new MediaScannerConnection.OnScanCompletedListener() {
            @Override
            public void onScanCompleted(String path, Uri uri) {
                completion.run(path);
            }
        });
    }

    private Map<String, String> imageMimeToExtension = new HashMap<String, String>() {{
        put("jpeg", ".jpg");
        put("png", ".png");
        put("gif", ".gif");
        put("webp", ".webp");
    }};

    // Legacy method for Android 9 and below
    private void saveMedia(Context context, CordovaInterface cordova, String fileName, String url, String album, Map<String, String> mimeToExtension, FilePathRunnable completion) throws IOException, URISyntaxException {
        File albumDirectory = makeAlbumInPhotoLibrary(album);
        File targetFile;
        if (url.startsWith("data:")) {
            Matcher matcher = dataURLPattern.matcher(url);
            if (!matcher.find()) {
                throw new IllegalArgumentException("The dataURL is in incorrect format");
            }
            String mime = matcher.group(2);
            int dataPos = matcher.end();
            String base64 = url.substring(dataPos);
            byte[] decoded = Base64.decode(base64, Base64.DEFAULT);
            if (decoded == null) {
                throw new IllegalArgumentException("The dataURL could not be decoded");
            }
            String extension = mimeToExtension.get(mime);
            if (extension == null) {
                extension = "." + mime;
            }
            targetFile = new File(albumDirectory, fileName + extension);
            FileOutputStream os = new FileOutputStream(targetFile);
            os.write(decoded);
            os.flush();
            os.close();
        } else {
            String extension = url.contains(".") ? url.substring(url.lastIndexOf(".")) : "";
            if (extension.contains("jpg") || extension.contains("JPG")) {
                extension = ".jpg";
            }
            if (extension.contains("png") || extension.contains("PNG")) {
                extension = ".png";
            }
            targetFile = new File(albumDirectory, fileName + extension);
            InputStream is;
            FileOutputStream os = new FileOutputStream(targetFile);
            if (url.startsWith("file:///android_asset/")) {
                String assetUrl = url.replace("file:///android_asset/", "");
                is = cordova.getActivity().getApplicationContext().getAssets().open(assetUrl);
            } else {
                is = new URL(url).openStream();
            }
            copyStream(is, os);
            os.flush();
            os.close();
            is.close();
        }
        addFileToMediaLibrary(context, targetFile, completion);
    }

    public interface ChunkResultRunnable {
        void run(ArrayList<JSONObject> chunk, int chunkNum, boolean isLastChunk);
    }

    public interface FilePathRunnable {
        void run(String filePath);
    }

    public interface JSONObjectRunnable {
        void run(JSONObject result);
    }
}
/*
 * Copyright (C) 2017-2023 The LineageOS Project
 * Copyright (C) 2020-2023 SHIFT GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blissroms.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONException;
import com.blissroms.updater.controller.UpdaterController;
import com.blissroms.updater.controller.UpdaterService;
import com.blissroms.updater.misc.StringGenerator;
import com.blissroms.updater.misc.Utils;
import com.blissroms.updater.model.Update;
import com.blissroms.updater.model.UpdateInfo;
import com.blissroms.updater.model.UpdateStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class UpdateImporter {
    private static final int REQUEST_PICK = 9061;
    private static final String TAG = "UpdateImporter";
    private static final String MIME_ZIP = "application/zip";
    private static final String FILE_NAME = "localUpdate.zip";
    private static final String METADATA_PATH = "META-INF/com/android/metadata";
    private static final String METADATA_TIMESTAMP_KEY = "post-timestamp=";

    private final Activity activity;
    private final Context context;
    private final Callbacks callbacks;

    private Thread workingThread;

    public UpdateImporter(Activity activity, Callbacks callbacks) {
        this.activity = activity;
        this.context = activity;
        this.callbacks = callbacks;
    }

    public UpdateImporter(Context context, Callbacks callbacks) {
        this.activity = null;
        this.context = context;
        this.callbacks = callbacks;
    }

    public void stopImport() {
        if (workingThread != null && workingThread.isAlive()) {
            workingThread.interrupt();
            workingThread = null;
        }
    }

    public void openImportPicker() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME_ZIP);
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    public boolean onResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK) {
            return false;
        }

        final ParcelFileDescriptor parcelDescriptor;
        try {
            parcelDescriptor = context.getContentResolver()
                    .openFileDescriptor(data.getData(), "r");
        } catch (FileNotFoundException e) {
            return false;
        }
        if (parcelDescriptor == null) {
            return false;
        }

        boolean ret = onPicked(parcelDescriptor);
        try {
            parcelDescriptor.close();
        } catch (IOException e) {
            return false;
        }
        return ret;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public boolean onPicked(ParcelFileDescriptor pfd) {
        callbacks.onImportStarted();

        workingThread = new Thread(() -> {
            File importedFile = null;
            try {
                importedFile = importFile(pfd);
                verifyPackage(importedFile);

                final Update update = buildLocalUpdate(importedFile);
                addUpdate(update);
                if (activity != null)
                    activity.runOnUiThread(() -> callbacks.onImportCompleted(update));
                else
                    callbacks.onImportCompleted(update);
            } catch (Exception e) {
                Log.e(TAG, "Failed to import update package", e);
                // Do not store invalid update
                if (importedFile != null) {
                    importedFile.delete();
                }

                if (activity != null)
                    activity.runOnUiThread(() -> callbacks.onImportCompleted(null));
                else
                    callbacks.onImportCompleted(null);
            }
        });
        workingThread.start();
        return true;
    }

    @SuppressLint("SetWorldReadable")
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private File importFile(ParcelFileDescriptor parcelDescriptor) throws IOException {
        final FileInputStream iStream = new FileInputStream(parcelDescriptor
                .getFileDescriptor());
        final File downloadDir = Utils.getDownloadPath(context);
        final File outFile = new File(downloadDir, FILE_NAME);
        if (outFile.exists()) {
            outFile.delete();
        }
        final FileOutputStream oStream = new FileOutputStream(outFile);

        int read;
        final byte[] buffer = new byte[4096];
        while ((read = iStream.read(buffer)) > 0) {
            oStream.write(buffer, 0, read);
        }
        oStream.flush();
        oStream.close();
        iStream.close();

        outFile.setReadable(true, false);

        return outFile;
    }

    private Update buildLocalUpdate(File file) {
        final long timeStamp = getTimeStamp(file);
        final String buildDate = StringGenerator.getDateLocalizedUTC(
                context, DateFormat.MEDIUM, timeStamp);
        final String name = context.getString(R.string.local_update_name);
        final Update update = new Update();
        update.setAvailableOnline(false);
        update.setName(name);
        update.setFile(file);
        update.setFileSize(file.length());
        update.setDownloadId(Update.LOCAL_ID);
        update.setTimestamp(timeStamp);
        update.setStatus(UpdateStatus.VERIFIED);
        update.setPersistentStatus(UpdateStatus.Persistent.VERIFIED);
        update.setVersion(String.format("%s (%s)", name, buildDate));
        return update;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void verifyPackage(File file) throws Exception {
        try {
            android.os.RecoverySystem.verifyPackage(file, null, null);
        } catch (Exception e) {
            if (file.exists()) {
                file.delete();
                throw new Exception("Verification failed, file has been deleted");
            } else {
                throw e;
            }
        }
    }

    private void addUpdate(Update update) {
        UpdaterController controller = UpdaterController.getInstance(context);
        controller.addUpdate(update, false);
    }

    private long getTimeStamp(File file) {
        try {
            final String metadataContent = readZippedFile(file, METADATA_PATH);
            final String[] lines = metadataContent.split("\n");
            for (String line : lines) {
                if (!line.startsWith(METADATA_TIMESTAMP_KEY)) {
                    continue;
                }

                final String timeStampStr = line.replace(METADATA_TIMESTAMP_KEY, "");
                return Long.parseLong(timeStampStr);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read date from local update zip package", e);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse timestamp number from zip metadata file", e);
        }

        Log.e(TAG, "Couldn't find timestamp in zip file, falling back to $now");
        return System.currentTimeMillis();
    }

    private String readZippedFile(File file, String path) throws IOException {
        final StringBuilder sb = new StringBuilder();
        InputStream iStream = null;

        try (final ZipFile zip = new ZipFile(file)) {
            final Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                final ZipEntry entry = iterator.nextElement();
                if (!METADATA_PATH.equals(entry.getName())) {
                    continue;
                }

                iStream = zip.getInputStream(entry);
                break;
            }

            if (iStream == null) {
                throw new FileNotFoundException("Couldn't find " + path + " in " + file.getName());
            }

            final byte[] buffer = new byte[1024];
            int read;
            while ((read = iStream.read(buffer)) > 0) {
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file from zip package", e);
            throw e;
        } finally {
            if (iStream != null) {
                iStream.close();
            }
        }

        return sb.toString();
    }

    public interface Callbacks {
        void onImportStarted();

        void onImportCompleted(Update update);
    }
}

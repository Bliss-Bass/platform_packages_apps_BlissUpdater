package com.blissroms.updater;

import static com.blissroms.updater.misc.Utils.triggerUpdate;
import static com.blissroms.updater.model.Update.UpdateToUpdateItemInfo;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.blissos.updatersdk.IUpdater;
import com.blissos.updatersdk.IUpdaterCallback;
import com.blissos.updatersdk.UpdateItemInfo;
import com.blissroms.updater.controller.UpdaterController;
import com.blissroms.updater.controller.UpdaterService;
import com.blissroms.updater.download.DownloadClient;
import com.blissroms.updater.misc.Constants;
import com.blissroms.updater.misc.Utils;
import com.blissroms.updater.model.Update;
import com.blissroms.updater.model.UpdateInfo;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UpdaterPublicService extends Service {
    private static final String TAG = UpdaterPublicService.class.getSimpleName();

    private Context mContext;
    private IUpdaterCallback mCallback;
    private UpdaterService mUpdaterService;

    @Override
    public void onCreate() {
        super.onCreate();

        mContext = this;
        mCallback = null;

        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mUpdaterService = null;
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mCallback == null) {
                Log.e(TAG, "No callback found");

                return;
            }

            if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
                try {
                    mCallback.onStatusChange(UpdateToUpdateItemInfo(new Update(update)));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction())) {
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
                try {
                    mCallback.onDownloadProgressChange(UpdateToUpdateItemInfo(new Update(update)));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else if (UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
                try {
                    mCallback.onInstallProgress(UpdateToUpdateItemInfo(new Update(update)));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private final IBinder mBinder = new IUpdater.Stub() {
        @Override
        public void setCallback(IUpdaterCallback cb) throws RemoteException {
            mCallback = cb;

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
            intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
            intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mBroadcastReceiver, intentFilter);
        }

        @Override
        public void checkForUpdates() throws RemoteException {
            if (!Utils.isNetworkAvailable(mContext)) {
                Log.d(TAG, "Network not available, scheduling new check");
                return;
            }

            final File json = Utils.getCachedUpdateList(mContext);
            final File jsonNew = new File(json.getAbsolutePath() + UUID.randomUUID());
            String url = Utils.getServerURL(mContext);
            Log.d(TAG, "Checking " + url);

            DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
                @Override
                public void onFailure(boolean cancelled) {
                    Log.e(TAG, "Could not download updates list, scheduling new check");
                    try {
                        if (mCallback != null)
                            mCallback.onUpdateCheckCompleted(false);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onResponse(DownloadClient.Headers headers) {
                }

                @Override
                public void onSuccess() {
                    try {
                        UpdaterController controller = mUpdaterService.getUpdaterController();
                        boolean newUpdates = false;

                        List<UpdateInfo> updates = Utils.parseJson(jsonNew, true);
                        List<String> updatesOnline = new ArrayList<>();
                        for (UpdateInfo update : updates) {
                            newUpdates |= controller.addUpdate(update);
                            updatesOnline.add(update.getDownloadId());
                        }
                        controller.setUpdatesAvailableOnline(updatesOnline, true);

                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
                        long currentMillis = System.currentTimeMillis();
                        preferences.edit()
                                .putLong(Constants.PREF_LAST_UPDATE_CHECK, currentMillis)
                                .apply();
                        if (json.exists() && Utils.isUpdateCheckEnabled(mContext) &&
                                Utils.checkForNewUpdates(json, jsonNew)) {
                            UpdatesCheckReceiver.updateRepeatingUpdatesCheck(mContext);
                        }
                        UpdatesCheckReceiver.cancelUpdatesCheck(mContext);
                        //noinspection ResultOfMethodCallIgnored
                        jsonNew.renameTo(json);

                        try {
                            if (mCallback != null)
                                mCallback.onUpdateCheckCompleted(newUpdates);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } catch (IOException | JSONException e) {
                        Log.e(TAG, "Could not parse list");
                        e.printStackTrace();
                        try {
                            if (mCallback != null)
                                mCallback.onUpdateCheckCompleted(false);
                        } catch (RemoteException r) {
                            r.printStackTrace();
                        }
                    }
                }
            };

            try {
                DownloadClient downloadClient = new DownloadClient.Builder()
                        .setUrl(url)
                        .setDestination(jsonNew)
                        .setDownloadCallback(callback)
                        .build();
                downloadClient.start();
            } catch (IOException e) {
                Log.e(TAG, "Could not fetch list");
                e.printStackTrace();

                try {
                    if (mCallback != null)
                        mCallback.onUpdateCheckCompleted(false);
                } catch (RemoteException r) {
                    r.printStackTrace();
                }
            }
        }

        @Override
        public List<UpdateItemInfo> getAvaliableUpdates() throws RemoteException {
            List<UpdateInfo> updates = mUpdaterService.getUpdaterController().getUpdates();
            List<UpdateItemInfo> ret = new ArrayList<>();

            for (UpdateInfo update: updates) {
                Update tmpUpdate = new Update(update);

                ret.add(UpdateToUpdateItemInfo(tmpUpdate));
            }

            return ret;
        }

        @Override
        public void downloadUpdate(String id) throws RemoteException {
            mUpdaterService.getUpdaterController().startDownload(id);
        }

        @Override
        public void pauseDownload(String id) throws RemoteException {
            final Intent intent = new Intent(mContext, UpdaterService.class);
            intent.setAction(UpdaterService.ACTION_DOWNLOAD_CONTROL);
            intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, id);
            intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_CONTROL, UpdaterService.DOWNLOAD_PAUSE);
            mContext.startService(intent);
        }

        @Override
        public void resumeDownload(String id) throws RemoteException {
            final Intent intent = new Intent(mContext, UpdaterService.class);
            intent.setAction(UpdaterService.ACTION_DOWNLOAD_CONTROL);
            intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, id);
            intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_CONTROL, UpdaterService.DOWNLOAD_RESUME);
            mContext.startService(intent);
        }

        @Override
        public void installUpdate(String id) throws RemoteException {
            triggerUpdate(mContext, id);
        }

        @Override
        public void cancelUpdate() throws RemoteException {
            final Intent intent = new Intent(mContext, UpdaterService.class);
            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
            mContext.startService(intent);
        }

        @Override
        public void suspendUpdate() throws RemoteException {
            final Intent intent = new Intent(mContext, UpdaterService.class);
            intent.setAction(UpdaterService.ACTION_INSTALL_SUSPEND);
            mContext.startService(intent);
        }

        @Override
        public void resumeUpdate() throws RemoteException {
            final Intent intent = new Intent(mContext, UpdaterService.class);
            intent.setAction(UpdaterService.ACTION_INSTALL_RESUME);
            mContext.startService(intent);
        }

        @Override
        public void importUpdate(ParcelFileDescriptor pfd) throws RemoteException {
            UpdateImporter ui = new UpdateImporter(mContext, new UpdateImporter.Callbacks() {
                @Override
                public void onImportStarted() {
                    try {
                        mCallback.onImportStarted();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onImportCompleted(Update update) {
                    try {
                        mCallback.onImportCompleted(UpdateToUpdateItemInfo(update));
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            });
            Thread importThread = new Thread(() -> ui.onPicked(pfd));
            importThread.start();
        }
    };
}

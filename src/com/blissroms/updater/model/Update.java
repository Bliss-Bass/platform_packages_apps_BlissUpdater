/*
 * Copyright (C) 2017 The LineageOS Project
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
package com.blissroms.updater.model;

import static com.blissroms.updater.misc.Utils.UpdateItemStatusToUpdateStatus;
import static com.blissroms.updater.misc.Utils.UpdateStatusToUpdateItemStatus;

import com.blissos.updatersdk.UpdateItemInfo;

import java.io.File;

public class Update extends UpdateBase implements UpdateInfo {
    public static final String LOCAL_ID = "local";

    private UpdateStatus mStatus = UpdateStatus.UNKNOWN;
    private int mPersistentStatus = UpdateStatus.Persistent.UNKNOWN;
    private File mFile;
    private int mProgress;
    private long mEta;
    private long mSpeed;
    private int mInstallProgress;
    private boolean mAvailableOnline;
    private boolean mIsFinalizing;

    public Update() {
    }

    public Update(UpdateInfo update) {
        super(update);
        mStatus = update.getStatus();
        mPersistentStatus = update.getPersistentStatus();
        mFile = update.getFile();
        mProgress = update.getProgress();
        mEta = update.getEta();
        mSpeed = update.getSpeed();
        mInstallProgress = update.getInstallProgress();
        mAvailableOnline = update.getAvailableOnline();
        mIsFinalizing = update.getFinalizing();
    }

    @Override
    public UpdateStatus getStatus() {
        return mStatus;
    }

    public void setStatus(UpdateStatus status) {
        mStatus = status;
    }

    @Override
    public int getPersistentStatus() {
        return mPersistentStatus;
    }

    public void setPersistentStatus(int status) {
        mPersistentStatus = status;
    }

    @Override
    public File getFile() {
        return mFile;
    }

    public void setFile(File file) {
        mFile = file;
    }

    @Override
    public int getProgress() {
        return mProgress;
    }

    public void setProgress(int progress) {
        mProgress = progress;
    }

    @Override
    public long getEta() {
        return mEta;
    }

    public void setEta(long eta) {
        mEta = eta;
    }

    @Override
    public long getSpeed() {
        return mSpeed;
    }

    public void setSpeed(long speed) {
        mSpeed = speed;
    }

    @Override
    public int getInstallProgress() {
        return mInstallProgress;
    }

    public void setInstallProgress(int progress) {
        mInstallProgress = progress;
    }

    @Override
    public boolean getAvailableOnline() {
        return mAvailableOnline;
    }

    public void setAvailableOnline(boolean availableOnline) {
        mAvailableOnline = availableOnline;
    }

    @Override
    public boolean getFinalizing() {
        return mIsFinalizing;
    }

    public void setFinalizing(boolean finalizing) {
        mIsFinalizing = finalizing;
    }

    public static Update UpdateItemInfoToUpdate(UpdateItemInfo update) {
        Update ret = new Update();
        if (update == null)
            return ret;

        ret.setName(update.name);
        ret.setDownloadUrl(update.downloadUrl);
        ret.setDownloadId(update.downloadId);
        ret.setTimestamp(update.timestamp);
        ret.setVersion(update.version);
        ret.setFileSize(update.fileSize);

        ret.setStatus(UpdateItemStatusToUpdateStatus(update.status));
        ret.setPersistentStatus(update.persistentStatus);
        ret.setProgress(update.progress);
        ret.setEta(update.eta);
        ret.setSpeed(update.speed);
        ret.setInstallProgress(update.installProgress);
        ret.setAvailableOnline(update.availableOnline);
        ret.setFinalizing(update.finalizing);

        return ret;
    }

    public static UpdateItemInfo UpdateToUpdateItemInfo(Update update) {
        UpdateItemInfo ret = new UpdateItemInfo();
        if (update == null)
            return ret;

        ret.name = update.getName();
        ret.downloadUrl = update.getDownloadUrl();
        ret.downloadId = update.getDownloadId();
        ret.timestamp = update.getTimestamp();
        ret.version = update.getVersion();
        ret.fileSize = update.getFileSize();

        ret.status = UpdateStatusToUpdateItemStatus(update.getStatus());
        ret.persistentStatus = update.getPersistentStatus();
        ret.progress = update.getProgress();
        ret.eta = update.getEta();
        ret.speed = update.getSpeed();
        ret.installProgress = update.getInstallProgress();
        ret.availableOnline = update.getAvailableOnline();
        ret.finalizing = update.getFinalizing();

        return ret;
    }
}

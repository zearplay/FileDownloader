/*
 * Copyright (c) 2015 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.liulishuo.filedownloader.services;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.ServiceCompat;

import com.liulishuo.filedownloader.PauseAllMarker;
import com.liulishuo.filedownloader.download.CustomComponentHolder;
import com.liulishuo.filedownloader.i.IFileDownloadIPCService;
import com.liulishuo.filedownloader.util.ExtraKeys;
import com.liulishuo.filedownloader.util.FileDownloadHelper;
import com.liulishuo.filedownloader.util.FileDownloadLog;
import com.liulishuo.filedownloader.util.FileDownloadProperties;
import com.liulishuo.filedownloader.util.FileDownloadUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The service is running for FileDownloader.
 * <p/>
 * You can add a command `process.non-separate=true` to the `filedownloader.properties` asset file
 * to make the FileDownloadService runs in the main process, and by default the FileDownloadService
 * runs in the separate process(`:filedownloader`).
 */
@SuppressLint("Registered")
public class FileDownloadService extends Service {

    private final Object lifecycleLock = new Object();
    private final List<PendingStartCommand> pendingStartCommands = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile IFileDownloadServiceHandler handler;
    private DeferredFileDownloadServiceHandler deferredHandler;
    private HandlerThread initializationThread;
    private PauseAllMarker pauseAllMarker;
    private boolean processNonSeparate;
    private boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        FileDownloadHelper.holdContext(this);

        try {
            FileDownloadUtils.setMinProgressStep(
                    FileDownloadProperties.getImpl().downloadMinProgressStep);
            FileDownloadUtils.setMinProgressTime(
                    FileDownloadProperties.getImpl().downloadMinProgressTime);
        } catch (IllegalAccessException e) {
            FileDownloadLog.e(this, e, "failed to configure progress callbacks");
        }

        processNonSeparate = FileDownloadProperties.getImpl().processNonSeparate;
        if (!processNonSeparate) {
            deferredHandler = new DeferredFileDownloadServiceHandler();
        }

        initializationThread = new HandlerThread(
                FileDownloadUtils.getThreadPoolName("ServiceInitializer"));
        initializationThread.start();
        new Handler(initializationThread.getLooper()).post(new Runnable() {
            @Override
            public void run() {
                initializeHandler();
            }
        });
    }

    private void initializeHandler() {
        try {
            final FileDownloadManager manager = new FileDownloadManager();
            final IFileDownloadServiceHandler initializedHandler = processNonSeparate
                    ? new FDServiceSharedHandler(new WeakReference<>(this), manager)
                    : new FDServiceSeparateHandler(new WeakReference<>(this), manager);

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    completeInitialization(initializedHandler);
                }
            });
        } catch (final Throwable throwable) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    FileDownloadLog.e(FileDownloadService.this, throwable,
                            "failed to initialize download service");
                    stopSelf();
                }
            });
        }
    }

    private void completeInitialization(IFileDownloadServiceHandler initializedHandler) {
        final List<PendingStartCommand> commands;
        synchronized (lifecycleLock) {
            if (destroyed) {
                initializedHandler.onDestroy();
                return;
            }

            handler = initializedHandler;
            commands = new ArrayList<>(pendingStartCommands);
            pendingStartCommands.clear();
        }

        if (deferredHandler != null) {
            deferredHandler.setDelegate(
                    (IFileDownloadIPCService) initializedHandler, initializedHandler);
        }

        PauseAllMarker.clearMarker();
        pauseAllMarker = new PauseAllMarker((IFileDownloadIPCService) initializedHandler);
        pauseAllMarker.startPauseAllLooperCheck();

        for (PendingStartCommand command : commands) {
            initializedHandler.onStartCommand(command.intent, command.flags, command.startId);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        inspectRunServiceForeground(intent);

        final IFileDownloadServiceHandler current = handler;
        if (current != null) {
            current.onStartCommand(intent, flags, startId);
        } else {
            synchronized (lifecycleLock) {
                if (!destroyed && handler == null) {
                    pendingStartCommands.add(new PendingStartCommand(intent, flags, startId));
                } else if (handler != null) {
                    handler.onStartCommand(intent, flags, startId);
                }
            }
        }

        return START_STICKY;
    }

    private void inspectRunServiceForeground(Intent intent) {
        if (intent == null) return;
        final boolean isForeground = intent.getBooleanExtra(ExtraKeys.IS_FOREGROUND, false);
        if (!isForeground) return;

        try {
            final ForegroundServiceConfig config = CustomComponentHolder.getImpl()
                    .getForegroundConfigInstance();
            if (config.isNeedRecreateChannelId()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                final NotificationChannel notificationChannel = new NotificationChannel(
                        config.getNotificationChannelId(),
                        config.getNotificationChannelName(),
                        NotificationManager.IMPORTANCE_LOW
                );
                final NotificationManager notificationManager =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager == null) {
                    FileDownloadLog.e(this, "notification manager is unavailable");
                    stopSelf();
                    return;
                }
                notificationManager.createNotificationChannel(notificationChannel);
            }

            final Notification notification = config.getNotification(this);
            if (notification == null) {
                FileDownloadLog.e(this, "foreground notification is null");
                stopSelf();
                return;
            }

            ServiceCompat.startForeground(
                    this,
                    config.getNotificationId(),
                    notification,
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
            if (FileDownloadLog.NEED_LOG) {
                FileDownloadLog.d(this, "run service foreground with config: %s", config);
            }
        } catch (RuntimeException e) {
            FileDownloadLog.e(this, e, "unable to enter foreground mode");
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        final IFileDownloadServiceHandler current;
        synchronized (lifecycleLock) {
            destroyed = true;
            pendingStartCommands.clear();
            current = handler;
            handler = null;
        }

        if (pauseAllMarker != null) {
            pauseAllMarker.stopPauseAllLooperCheck();
            pauseAllMarker = null;
        }

        if (deferredHandler != null) {
            deferredHandler.onDestroy();
            deferredHandler = null;
        } else if (current != null) {
            current.onDestroy();
        }

        if (initializationThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                initializationThread.quitSafely();
            } else {
                initializationThread.quit();
            }
            initializationThread = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (deferredHandler != null) return deferredHandler.onBind(intent);

        final IFileDownloadServiceHandler current = handler;
        return current == null ? null : current.onBind(intent);
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        FileDownloadLog.w(this, "foreground data-sync timeout; stopping service");
        final IFileDownloadServiceHandler current = handler;
        if (current instanceof IFileDownloadIPCService) {
            try {
                ((IFileDownloadIPCService) current).pauseAllTasks();
            } catch (Exception e) {
                FileDownloadLog.e(this, e, "failed to pause tasks after timeout");
            }
        }
        stopSelf(startId);
    }

    public static class SharedMainProcessService extends FileDownloadService {
    }

    public static class SeparateProcessService extends FileDownloadService {
    }

    private static final class PendingStartCommand {
        private final Intent intent;
        private final int flags;
        private final int startId;

        private PendingStartCommand(Intent intent, int flags, int startId) {
            this.intent = intent;
            this.flags = flags;
            this.startId = startId;
        }
    }
}

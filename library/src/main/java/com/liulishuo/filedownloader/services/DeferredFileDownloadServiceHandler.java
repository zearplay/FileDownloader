/*
 * Copyright (c) 2015 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.liulishuo.filedownloader.services;

import android.app.Notification;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.liulishuo.filedownloader.i.IFileDownloadIPCCallback;
import com.liulishuo.filedownloader.i.IFileDownloadIPCService;
import com.liulishuo.filedownloader.model.FileDownloadHeader;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.liulishuo.filedownloader.util.FileDownloadLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A non-blocking binder used while the database and download manager are initialized.
 *
 * <p>Service creation runs on the Android main thread. Returning this binder immediately avoids
 * blocking that thread on database migration and file-system validation. Mutating calls are
 * replayed in order after the real handler is ready; query calls return safe defaults.</p>
 */
final class DeferredFileDownloadServiceHandler extends IFileDownloadIPCService.Stub
        implements IFileDownloadServiceHandler {

    private static final int MAX_PENDING_OPERATIONS = 1024;

    private final Object lock = new Object();
    private final ArrayDeque<PendingOperation> pendingOperations = new ArrayDeque<>();
    private final RemoteCallbackList<IFileDownloadIPCCallback> pendingCallbacks =
            new RemoteCallbackList<>();

    private volatile IFileDownloadIPCService delegate;
    private volatile IFileDownloadServiceHandler lifecycleDelegate;
    private boolean destroyed;

    void setDelegate(IFileDownloadIPCService ipcDelegate,
                     IFileDownloadServiceHandler serviceDelegate) {
        final List<IFileDownloadIPCCallback> callbacks = new ArrayList<>();
        final List<PendingOperation> operations = new ArrayList<>();
        boolean destroyDelegate = false;

        synchronized (lock) {
            if (destroyed) {
                destroyDelegate = true;
            } else {
                delegate = ipcDelegate;
                lifecycleDelegate = serviceDelegate;

                final int count = pendingCallbacks.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        callbacks.add(pendingCallbacks.getBroadcastItem(i));
                    }
                } finally {
                    pendingCallbacks.finishBroadcast();
                    pendingCallbacks.kill();
                }

                operations.addAll(pendingOperations);
                pendingOperations.clear();
            }
        }

        if (destroyDelegate) {
            serviceDelegate.onDestroy();
            return;
        }

        for (IFileDownloadIPCCallback callback : callbacks) {
            try {
                ipcDelegate.registerCallback(callback);
            } catch (RemoteException e) {
                FileDownloadLog.e(this, e, "register deferred callback failed");
            }
        }

        for (PendingOperation operation : operations) {
            runOperation(ipcDelegate, operation);
        }
    }

    @Override
    public void registerCallback(IFileDownloadIPCCallback callback) throws RemoteException {
        final IFileDownloadIPCService current;
        synchronized (lock) {
            current = delegate;
            if (current == null && !destroyed) {
                pendingCallbacks.register(callback);
                return;
            }
        }

        if (current != null) {
            current.registerCallback(callback);
        }
    }

    @Override
    public void unregisterCallback(IFileDownloadIPCCallback callback) throws RemoteException {
        final IFileDownloadIPCService current;
        synchronized (lock) {
            current = delegate;
            if (current == null) {
                pendingCallbacks.unregister(callback);
                return;
            }
        }

        current.unregisterCallback(callback);
    }

    @Override
    public boolean checkDownloading(String url, String path) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        return current != null && current.checkDownloading(url, path);
    }

    @Override
    public void start(final String url, final String path, final boolean pathAsDirectory,
                      final int callbackProgressTimes,
                      final int callbackProgressMinIntervalMillis,
                      final int autoRetryTimes, final boolean forceReDownload,
                      final FileDownloadHeader header, final boolean isWifiRequired) {
        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.start(url, path, pathAsDirectory, callbackProgressTimes,
                        callbackProgressMinIntervalMillis, autoRetryTimes, forceReDownload,
                        header, isWifiRequired);
            }
        });
    }

    @Override
    public boolean pause(final int downloadId) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        if (current != null) return current.pause(downloadId);

        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.pause(downloadId);
            }
        });
        return false;
    }

    @Override
    public void pauseAllTasks() {
        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.pauseAllTasks();
            }
        });
    }

    @Override
    public boolean setMaxNetworkThreadCount(final int count) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        if (current != null) return current.setMaxNetworkThreadCount(count);

        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.setMaxNetworkThreadCount(count);
            }
        });
        return false;
    }

    @Override
    public long getSofar(int downloadId) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        return current == null ? 0 : current.getSofar(downloadId);
    }

    @Override
    public long getTotal(int downloadId) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        return current == null ? 0 : current.getTotal(downloadId);
    }

    @Override
    public byte getStatus(int downloadId) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        return current == null
                ? FileDownloadStatus.INVALID_STATUS : current.getStatus(downloadId);
    }

    @Override
    public boolean isIdle() throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        return current == null || current.isIdle();
    }

    @Override
    public void startForeground(final int id, final Notification notification) {
        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.startForeground(id, notification);
            }
        });
    }

    @Override
    public void stopForeground(final boolean removeNotification) {
        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.stopForeground(removeNotification);
            }
        });
    }

    @Override
    public boolean clearTaskData(final int id) throws RemoteException {
        final IFileDownloadIPCService current = delegate;
        if (current != null) return current.clearTaskData(id);

        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.clearTaskData(id);
            }
        });
        return false;
    }

    @Override
    public void clearAllTaskData() {
        enqueue(new PendingOperation() {
            @Override
            public void run(IFileDownloadIPCService service) throws RemoteException {
                service.clearAllTaskData();
            }
        });
    }

    @Override
    public void onStartCommand(Intent intent, int flags, int startId) {
        // FileDownloadService queues lifecycle commands until initialization completes.
    }

    @Override
    public IBinder onBind(Intent intent) {
        return this;
    }

    @Override
    public void onDestroy() {
        final IFileDownloadServiceHandler current;
        synchronized (lock) {
            if (destroyed) return;
            destroyed = true;
            pendingOperations.clear();
            pendingCallbacks.kill();
            current = lifecycleDelegate;
            delegate = null;
            lifecycleDelegate = null;
        }

        if (current != null) current.onDestroy();
    }

    private void enqueue(PendingOperation operation) {
        final IFileDownloadIPCService current;
        synchronized (lock) {
            current = delegate;
            if (current == null) {
                if (destroyed) return;
                if (pendingOperations.size() >= MAX_PENDING_OPERATIONS) {
                    pendingOperations.removeFirst();
                    FileDownloadLog.w(this, "too many deferred operations; dropping oldest");
                }
                pendingOperations.addLast(operation);
                return;
            }
        }

        runOperation(current, operation);
    }

    private void runOperation(IFileDownloadIPCService service, PendingOperation operation) {
        try {
            operation.run(service);
        } catch (RemoteException e) {
            FileDownloadLog.e(this, e, "deferred operation failed");
        } catch (RuntimeException e) {
            FileDownloadLog.e(this, e, "deferred operation crashed");
        }
    }

    private interface PendingOperation {
        void run(IFileDownloadIPCService service) throws RemoteException;
    }
}

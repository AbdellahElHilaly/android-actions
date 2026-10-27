package com.htmlshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.htmlshelf.core.SiteHttpServer;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Keeps local sites available after Chrome/Firefox is opened. */
public final class SiteService extends Service {
    public static final String ACTION_STOP = "com.htmlshelf.STOP_SITES";
    private static final String CHANNEL = "site_host";
    private static final int NOTIFICATION_ID = 41;
    private final Map<Long, SiteHttpServer> active = new HashMap<>();
    private final IBinder binder = new LocalBinder();

    public final class LocalBinder extends Binder {
        SiteService service() { return SiteService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "المواقع المفتوحة", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("تشغيل ملفات HTML المحلية في المتصفح الخارجي");
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent stop = new Intent(this, SiteService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 7, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("صفحاتي")
                .setContentText("المواقع المحلية متاحة في المتصفح")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "إيقاف", stopAction).build())
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    /** Call only from Activity, while the user is interacting with it. */
    public synchronized String open(long pageId) throws IOException {
        SiteHttpServer existing = active.get(pageId);
        if (existing != null) return existing.launchUrl();
        PageRepository.Page page;
        try (PageRepository repo = new PageRepository(this)) { page = repo.find(pageId); }
        if (page == null) throw new IOException("Page deleted");
        File sites = new File(getFilesDir(), "sites");
        File folder = new File(sites, page.folder).getCanonicalFile();
        if (!folder.getParentFile().equals(sites.getCanonicalFile()) || !folder.isDirectory()) {
            throw new IOException("Missing site folder");
        }
        File root = new File(folder, page.root).getCanonicalFile();
        if (!root.toPath().startsWith(folder.toPath())) throw new IOException("Unsafe site path");
        SiteHttpServer server = new SiteHttpServer(root);
        active.put(pageId, server);
        return server.launchUrl();
    }

    /** Release files on deletion; prevents serving a deleted site. */
    public synchronized void closeSite(long id) {
        SiteHttpServer server = active.remove(id);
        if (server != null) server.close();
    }

    @Override public synchronized void onDestroy() {
        for (SiteHttpServer server : active.values()) server.close();
        active.clear();
        super.onDestroy();
    }
}

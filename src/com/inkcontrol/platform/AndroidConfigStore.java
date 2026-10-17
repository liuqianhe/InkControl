package com.inkcontrol.platform;

import android.content.Context;
import android.util.AtomicFile;
import com.inkcontrol.core.ConfigStore;
import com.inkcontrol.core.Configuration;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class AndroidConfigStore implements ConfigStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final Context context;
    public AndroidConfigStore(Context context) {
        this.context = context.createDeviceProtectedStorageContext();
        file = new AtomicFile(new File(this.context.getFilesDir(), "configuration.txt"));
    }
    @Override public Configuration load() throws Exception {
        synchronized (LOCK) {
            if (!file.getBaseFile().exists()) return new Configuration();
            return Configuration.parse(new String(file.readFully(), StandardCharsets.UTF_8));
        }
    }
    @Override public void save(Configuration configuration) throws Exception {
        synchronized (LOCK) {
            FileOutputStream out = file.startWrite();
            try {
                out.write(configuration.text().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
            } catch (Exception error) {
                file.failWrite(out);
                throw error;
            }
        }
        context.getContentResolver().notifyChange(android.net.Uri.withAppendedPath(ConfigProvider.URI, "config"), null);
    }
}

package app.aomarchy;

import android.os.ParcelFileDescriptor;

interface ICompatibleBridge {
    ParcelFileDescriptor getConnection();
}
